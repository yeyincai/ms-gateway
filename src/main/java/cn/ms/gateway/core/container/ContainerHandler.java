package cn.ms.gateway.core.container;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.ThreadContext;

import cn.ms.gateway.base.ICallback;
import cn.ms.gateway.common.Constants;
import cn.ms.gateway.common.TradeIdWorker;
import cn.ms.gateway.common.log.Logger;
import cn.ms.gateway.common.log.LoggerFactory;
import cn.ms.gateway.core.AssemblySupport;
import cn.ms.gateway.entity.GatewayREQ;
import cn.ms.gateway.entity.GatewayRES;

public class ContainerHandler extends ChannelInboundHandlerAdapter {
    
	private Logger logger=LoggerFactory.getLogger(ContainerHandler.class);
	
	private ICallback<GatewayREQ, GatewayRES, FullHttpRequest> callback;
	private FullHttpRequest request;
	private TradeIdWorker tradeIdWorker=new TradeIdWorker(0, 0);
	
	public ContainerHandler(ICallback<GatewayREQ, GatewayRES, FullHttpRequest> callback) {
		this.callback=callback;
	}
    
	@Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpRequest) {
            request = (FullHttpRequest) msg;
            callback.before(request);
        }
        if (msg instanceof HttpContent) {
        	long tradeStartTime=System.currentTimeMillis();
        	String tradeId=String.valueOf(tradeIdWorker.nextId());
        	ThreadContext.put(Constants.TRADEID_KEY, tradeId);
        	logger.info("=====交易开始=====");
        	
        	final GatewayREQ gatewayREQ=new GatewayREQ();
            gatewayREQ.setTradeId(tradeId);
            gatewayREQ.setTradeStartTime(tradeStartTime);
        	
            //$NON-NLS-获取客户端端IP$
            String clientIP = request.headers().get("X-Forwarded-For");
            if (clientIP == null) {
                InetSocketAddress insocket = (InetSocketAddress) ctx.channel().remoteAddress();
                clientIP = insocket.getAddress().getHostAddress();
            }
            gatewayREQ.setClientHost(clientIP);
            
            //$NON-NLS-读取参数$
            gatewayREQ.putAllParameter(new QueryStringDecoder(request.uri()).parameters());
            if(!gatewayREQ.getParameters().isEmpty()){
            	for(Map.Entry<String, List<String>> entry:gatewayREQ.getParameters().entrySet()){
                	if(entry.getValue().size()==1){
                		gatewayREQ.putParam(entry.getKey(), entry.getValue().get(0));
                	}
                }	
            }
            
            //$NON-NLS-读取请求报文$
            ByteBuf buf = null;
            String content = null;
            try {
            	HttpContent httpContent = (HttpContent) msg;
            	buf = httpContent.content();
                content=buf.toString(io.netty.util.CharsetUtil.UTF_8);
			} finally {
				buf.release();
			}
            
            gatewayREQ.setContent(content);
            gatewayREQ.setRequest(request);
            gatewayREQ.setCtx(ctx);
            
            try {
            	GatewayRES gatewayRES = callback.callback(gatewayREQ);
            	if(gatewayRES!=null){
            		//$NON-NLS-组装响应结果$
            		AssemblySupport.HttpServerResponse(gatewayREQ, gatewayRES);
            	}
			} catch (Throwable t) {
				logger.error(t, "微服务网关处理异常: %s", t.getMessage());
			}
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    	logger.error(cause, "微服务网关Netty接入异常: %s", cause.getMessage());
        ctx.close();
    }

}
