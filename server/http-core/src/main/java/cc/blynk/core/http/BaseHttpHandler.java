package cc.blynk.core.http;

import cc.blynk.core.http.rest.HandlerHolder;
import cc.blynk.core.http.rest.HandlerRegistry;
import cc.blynk.core.http.rest.URIDecoder;
import cc.blynk.server.core.dao.SessionDao;
import cc.blynk.server.core.dao.UserDao;
import cc.blynk.server.core.protocol.enums.Command;
import cc.blynk.server.core.protocol.handlers.DefaultExceptionHandler;
import cc.blynk.server.core.stats.GlobalStats;
import cc.blynk.server.handlers.DefaultReregisterHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The Blynk Project.
 * Created by Dmitriy Dumanskiy.
 * Created on 24.12.15.
 */
public abstract class BaseHttpHandler extends ChannelInboundHandlerAdapter implements DefaultReregisterHandler, DefaultExceptionHandler {

    protected static final Logger log = LogManager.getLogger(BaseHttpHandler.class);

    protected final UserDao userDao;
    protected final SessionDao sessionDao;
    protected final GlobalStats globalStats;

    public BaseHttpHandler(UserDao userDao, SessionDao sessionDao, GlobalStats globalStats) {
        this.userDao = userDao;
        this.sessionDao = sessionDao;
        this.globalStats = globalStats;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof HttpRequest)) {
            return;
        }

        HttpRequest req = (HttpRequest) msg;

        log.info("{} : {}", req.getMethod().name(), req.getUri());

        globalStats.mark(Command.HTTP_TOTAL);

        processHttp(ctx, req);
    }

    public void processHttp(ChannelHandlerContext ctx, HttpRequest req) {
        HandlerHolder handlerHolder = HandlerRegistry.findHandler(req.getMethod(), HandlerRegistry.path(req.getUri()));

        if (handlerHolder == null) {
            log.error("Error resolving url. No path found. {} : {}", req.getMethod().name(), req.getUri());
            ReferenceCountUtil.release(req);
            ctx.writeAndFlush(Response.notFound());
            return;
        }

        URIDecoder uriDecoder = new URIDecoder(req.getUri());
        HandlerRegistry.populateBody(req, uriDecoder);

        Object[] params;
        try {
            uriDecoder.pathData = handlerHolder.uriTemplate.extractParameters();
            params = handlerHolder.fetchParams(uriDecoder);
        } catch (StringIndexOutOfBoundsException stringE) {
            log.error("{} : '{}'. Error : ", req.getMethod().name(), req.getUri(), stringE.getMessage());
            ctx.writeAndFlush(Response.serverError(stringE.getMessage()));
            return;
        } catch (Exception e) {
            ctx.writeAndFlush(Response.serverError(e.getMessage()));
            return;
        } finally {
            ReferenceCountUtil.release(req);
        }

        finishHttp(ctx, uriDecoder, handlerHolder, params);
    }

    public abstract void finishHttp(ChannelHandlerContext ctx, URIDecoder uriDecoder, HandlerHolder handlerHolder, Object[] params);

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        handleUnexpectedException(ctx, cause);
    }

}
