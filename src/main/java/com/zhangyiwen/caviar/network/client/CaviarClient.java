package com.zhangyiwen.caviar.network.client;

import com.zhangyiwen.caviar.network.dispatcher.ClientEventDispatcher;
import com.zhangyiwen.caviar.network.dispatcher.EventDispatcher;
import com.zhangyiwen.caviar.network.exception.CaviarNetworkException;
import com.zhangyiwen.caviar.network.handler.NetworkEventHandler;
import com.zhangyiwen.caviar.network.request.CaviarMsgCallback;
import com.zhangyiwen.caviar.network.request.RequestContext;
import com.zhangyiwen.caviar.network.request.RequestContextManager;
import com.zhangyiwen.caviar.network.session.NettySessionContext;
import com.zhangyiwen.caviar.network.session.SessionContext;
import com.zhangyiwen.caviar.protocol.CaviarDecoder;
import com.zhangyiwen.caviar.protocol.CaviarEncoder;
import com.zhangyiwen.caviar.protocol.CaviarMessage;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.Data;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Created by zhangyiwen on 2017/12/20.
 * 网络客户端
 */
@Data
public class CaviarClient implements Client{

    private static final Logger LOGGER = LoggerFactory.getLogger(CaviarClient.class);

    private static final String WORKER_THREAD_NAME_PATTERN = "io-worker-%d";

    private static final int WRITE_WAIT_SECONDS = 5;     // 设置5秒检测chanel是否发送过数据

    private volatile boolean running;           //Client连接成功状态

    private EventLoopGroup worker =
            new NioEventLoopGroup(Runtime.getRuntime().availableProcessors() + 1, new BasicThreadFactory.Builder().namingPattern(WORKER_THREAD_NAME_PATTERN).build());

    ScheduledExecutorService callBackTimeoutExecutor = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors()*2 + 1);     //异步超时监听线程池

    private EventDispatcher eventDispatcher;

    private ChannelHandler eventHandler;

    private Bootstrap bootstrap;

    private Channel channel;

    private String host;

    private int port;

    private long connectTimeout;                        //连接超时时间
    private long requestTimeout;                        //请求超时时间

    public CaviarClient(long connectTimeout,long requestTimeout,CaviarClientBizListener clientBizListener) {
        this.eventDispatcher = new ClientEventDispatcher(this,clientBizListener);
        this.eventHandler = new NetworkEventHandler(eventDispatcher);
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
    }

    //==================== network ====================>
    @Override
    public void connect(String host, int port) throws CaviarNetworkException {
        this.running = false;
        this.host = host;
        this.port = port;

        bootstrap = new Bootstrap();

        ChannelFuture channelFuture;
        bootstrap.group(worker);
        bootstrap.channel(NioSocketChannel.class).handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
                ChannelPipeline p = ch.pipeline();
                p.addLast("idle-handler",new IdleStateHandler(0, WRITE_WAIT_SECONDS, 0, TimeUnit.SECONDS));
                p.addLast("connector-encoder", new CaviarEncoder());
                p.addLast("connector-decoder", new CaviarDecoder());
                p.addLast("msg-handler", eventHandler);
            }
        });
        LOGGER.info("[CaviarClient] connect start...");
        channelFuture = bootstrap.connect(host,port);
        this.channel = channelFuture.channel();
        channelFuture.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture f) throws Exception {
                if (f.isSuccess()) {
                    LOGGER.info("[CaviarClient] connect get resp. -> success");
                } else {
                    LOGGER.info("[CaviarClient] connect get resp. -> fail");
                }
            }
        });

        try {
            synchronized (this){
                LOGGER.info("[CaviarClient] connect wait resp...");
                this.wait(connectTimeout);
            }
        } catch (InterruptedException e) {
            throw new CaviarNetworkException("connect await done interrupted.");
        }
        if(!this.running){
            throw CaviarNetworkException.CLIENT_CONNECT_TIMEOUT;
        }
        LOGGER.info("[CaviarClient] connect end.");
    }

    @Override
    public void reconnect() {
        LOGGER.info("[CaviarClient] reconnect start...");
        this.running = false;
        bootstrap = new Bootstrap();
        bootstrap.group(worker);
        bootstrap.channel(NioSocketChannel.class).handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
                ChannelPipeline p = ch.pipeline();
                p.addLast("idle-handler",new IdleStateHandler(0, WRITE_WAIT_SECONDS, 0, TimeUnit.SECONDS));
                p.addLast("connector-encoder", new CaviarEncoder());
                p.addLast("connector-decoder", new CaviarDecoder());
                p.addLast("msg-handler", eventHandler);
            }
        });
        ChannelFuture channelFuture = bootstrap.connect(host, port);
        this.channel = channelFuture.channel();
        channelFuture.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture f) throws Exception {
                if (f.isSuccess()) {
                    LOGGER.info("[CaviarClient] reconnect get resp. -> success");
                } else {
                    LOGGER.info("[CaviarClient] reconnect get resp. -> fail");
                }
            }
        });
        try {
            synchronized (this){
                LOGGER.info("[CaviarClient] reconnect wait resp...");
                this.wait(connectTimeout);
            }
        } catch (InterruptedException e) {
            running = false;
            LOGGER.warn("[CaviarClient] reconnect wait interrupted...");
        }
        //失败重连
        if(!running){
            LOGGER.info("[CaviarClient] reconnect failed. retry.");
            channel.eventLoop().schedule(new Runnable() {
                @Override
                public void run() {
                    reconnect();
                }
            }, 2, TimeUnit.SECONDS);
        }
        else {
            LOGGER.info("[CaviarClient] reconnect succeed.");
        }
    }

//    @Override
//    public void disconnect() {
//        LOGGER.info("[CaviarClient] disconnect start.");
//        this.running = false;
//        if (channel != null && channel.isOpen()) {
//            channel.close().awaitUninterruptibly();
//        }
//        LOGGER.info("[CaviarClient] disconnect end.");
//    }

    @Override
    public void close() throws IOException {
        LOGGER.info("[CaviarClient] close start...");
        //
        this.running = false;
        if (channel != null) {
            channel.close().awaitUninterruptibly();
        }
        worker.shutdownGracefully();
        //
        this.eventDispatcher.close();
        //
        try {
            callBackTimeoutExecutor.awaitTermination(5, TimeUnit.SECONDS);
            callBackTimeoutExecutor.shutdown();
            NettySessionContext.serverCallBackTimeoutExecutor.awaitTermination(5, TimeUnit.SECONDS);
            NettySessionContext.serverCallBackTimeoutExecutor.shutdown();
        } catch (InterruptedException e) {
            LOGGER.error("[CaviarClient] close. error.", e);
        }
        LOGGER.info("[CaviarClient] close end.");
    }

    @Override
    public void setRunningState(boolean running) {
        this.running = running;
    }

    //==================== msg ====================>
    @Override
    public byte[] login(byte[] loginMsg) throws CaviarNetworkException{
        if(!running){
            throw CaviarNetworkException.CLIENT_NOT_RUNNING;
        }
        LOGGER.info("[CaviarClient] login start.");
        CaviarMessage caviarMessage = CaviarMessage.CLIENT_LOGIN_REQ(loginMsg);
        SessionContext sessionContext = getSessionContext();

        long requestId = caviarMessage.getRequestId();
        RequestContext requestContext = new RequestContext(sessionContext.getIndex(),requestId,caviarMessage);
        RequestContextManager.getClientRequestContextManager().bindRequestContext(requestId,requestContext);

        sessionContext.writeAndFlush(caviarMessage);
        try {
            synchronized (requestContext){
                LOGGER.info("[CaviarClient] login wait resp...");
                requestContext.wait(requestTimeout);
            }
        } catch (InterruptedException e) {
            throw new CaviarNetworkException("login await done interrupted.");
        }

        RequestContext requestContextResp = RequestContextManager.getClientRequestContextManager().getRequestContext(requestId);
        CaviarMessage response = requestContextResp.getResponseMessage();
        RequestContextManager.getClientRequestContextManager().cleanRequestContext(requestId);
        LOGGER.info("[CaviarClient] login end. resp:{}",response);
        if(response == null){
            throw CaviarNetworkException.CLIENT_REQ_TIMEOUT;
        }
        return response.getMsgBody();
    }

    @Override
    public byte[] logout(byte[] logoutMsg) throws CaviarNetworkException{
        if(!running){
            throw CaviarNetworkException.CLIENT_NOT_RUNNING;
        }
        LOGGER.info("[CaviarClient] logout start.");
        CaviarMessage caviarMessage = CaviarMessage.CLIENT_LOGOUT_REQ(logoutMsg);
        SessionContext sessionContext = getSessionContext();

        long requestId = caviarMessage.getRequestId();
        RequestContext requestContext = new RequestContext(sessionContext.getIndex(),requestId,caviarMessage);
        RequestContextManager.getClientRequestContextManager().bindRequestContext(requestId,requestContext);

        sessionContext.writeAndFlush(caviarMessage);
        try {
            synchronized (requestContext){
                LOGGER.info("[CaviarClient] logout wait resp...");
                requestContext.wait(requestTimeout);
            }
        } catch (InterruptedException e) {
            throw new CaviarNetworkException("logout await done interrupted.");
        }

        RequestContext requestContextResp = RequestContextManager.getClientRequestContextManager().getRequestContext(requestId);
        CaviarMessage response = requestContextResp.getResponseMessage();
        RequestContextManager.getClientRequestContextManager().cleanRequestContext(requestId);
        LOGGER.info("[CaviarClient] logout end. resp:{}",response);
        if(response == null){
            throw CaviarNetworkException.CLIENT_REQ_TIMEOUT;
        }
        return response.getMsgBody();
    }

    @Override
    public byte[] sendMsgSync(byte[] msg) throws CaviarNetworkException {
        if(!running){
            throw CaviarNetworkException.CLIENT_NOT_RUNNING;
        }
        LOGGER.info("[CaviarClient] sendMsgSync start.");
        CaviarMessage caviarMessage = CaviarMessage.CLIENT_MSG_SEND_REQ(msg);
        SessionContext sessionContext = getSessionContext();

        long requestId = caviarMessage.getRequestId();
        RequestContext requestContext = new RequestContext(sessionContext.getIndex(),requestId,caviarMessage);
        RequestContextManager.getClientRequestContextManager().bindRequestContext(requestId,requestContext);

        sessionContext.writeAndFlush(caviarMessage);
        try {
            synchronized (requestContext){
                LOGGER.info("[CaviarClient] sendMsgSync wait resp...");
                requestContext.wait(requestTimeout);
            }
        } catch (InterruptedException e) {
            throw new CaviarNetworkException("sendMsgSync await done interrupted.");
        }

        RequestContext requestContextResp = RequestContextManager.getClientRequestContextManager().getRequestContext(requestId);
        CaviarMessage response = requestContextResp.getResponseMessage();
        RequestContextManager.getClientRequestContextManager().cleanRequestContext(requestId);
        LOGGER.info("[CaviarClient] sendMsgSync end. resp:{}",response);
        if(response == null){
            throw CaviarNetworkException.CLIENT_REQ_TIMEOUT;
        }
        return response.getMsgBody();
    }

    @Override
    public void sendMsgAsync(byte[] msg, CaviarMsgCallback caviarMsgCallback) {
        if(!running){
            throw CaviarNetworkException.CLIENT_NOT_RUNNING;
        }
        CaviarMessage caviarMessage = CaviarMessage.CLIENT_MSG_SEND_ASYNC_REQ(msg);
        SessionContext sessionContext = getSessionContext();

        long requestId = caviarMessage.getRequestId();
        RequestContext requestContext = new RequestContext(sessionContext.getIndex(),requestId,caviarMessage,caviarMsgCallback);
        RequestContextManager.getClientRequestContextManager().bindRequestContext(requestId, requestContext);

        sessionContext.writeAndFlush(caviarMessage);
        LOGGER.info("[CaviarClient] sendMsgAsync. requestId:{}, msg:{}",requestId,msg);

        callBackTimeoutExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                RequestContext timeoutRequestContext = RequestContextManager.getClientRequestContextManager().getRequestContext(requestId);
                if (timeoutRequestContext != null && timeoutRequestContext.markRespHandled()) {
                    LOGGER.info("[CaviarClient] sendMsgAsync timeout. requestTimeout:{}, requestId:{},msg:{}", requestTimeout, requestId, msg);
                    timeoutRequestContext.setRespHandled(true);
                    RequestContextManager.getClientRequestContextManager().cleanRequestContext(requestId);
                    caviarMsgCallback.dealRequestTimeout(msg);
                }
            }
        }, 5000, TimeUnit.MILLISECONDS);
    }

    /**
     * 获取sessionContext
     * @return SessionContext
     */
    private SessionContext getSessionContext(){
        return this.channel.attr(NettySessionContext.session).get();
    }
}
