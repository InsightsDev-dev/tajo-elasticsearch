package nta.rpc;

import java.io.ByteArrayInputStream;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import nta.rpc.WritableRpcProtos.Invocation;
import nta.rpc.WritableRpcProtos.Response;
import org.apache.hadoop.io.Writable;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;

import com.google.protobuf.ByteString;

public class WritableRpcServer extends NettyServerBase {
  private static Log LOG = LogFactory.getLog(WritableRpcServer.class);
  private final Object instance;
  private final Class<?> clazz;
  private final ChannelPipelineFactory pipeline;
  private Map<String, Method> methods;

  public WritableRpcServer(Object proxy, InetSocketAddress bindAddress) {
    super(bindAddress);
    this.instance = proxy;
    this.clazz = instance.getClass();
    this.methods = new HashMap<String, Method>();
    this.pipeline = new ProtoPipelineFactory(new ServerHandler(),
        Invocation.getDefaultInstance());

    super.init(this.pipeline);

    for (Method m : this.clazz.getMethods()) {
      methods.put(m.getName(), m);
    }
  }

  private class ServerHandler extends SimpleChannelUpstreamHandler {
    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e)
        throws Exception {
      final Invocation request = (Invocation) e.getMessage();
      String methodName = request.getMethodName();

      if (methods.containsKey(methodName) == false) {
        throw new NoSuchMethodException(methodName);
      }

      Method method = methods.get(methodName);
      Object[] objs = null;
      int size = method.getParameterTypes().length;
      if (size > 0) {
        objs = new Object[size];
        for (int i = 0; i < size; i++) {
          objs[i] = method.getParameterTypes()[i].newInstance();
          ByteArrayInputStream bis = new ByteArrayInputStream(request.getParam(
              i).toByteArray());
          DataInputStream dis = new DataInputStream(bis);
          ((Writable) objs[i]).readFields(dis);
        }
      }

      Writable res = (Writable) method.invoke(instance, objs);
      Response response = null;
      if (res == null) {
        response = Response.newBuilder().setId(request.getId())
            .setHasReturn(false).build();
      } else {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        res.write(dos);
        dos.flush();
        baos.flush();
        ByteString str = ByteString.copyFrom(baos.toByteArray());
        response = Response.newBuilder().setId(request.getId())
            .setHasReturn(true).setReturnValue(str).build();
      }
      e.getChannel().write(response);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
      e.getChannel().close();
      LOG.error(e.getCause());
    }
  }
}
