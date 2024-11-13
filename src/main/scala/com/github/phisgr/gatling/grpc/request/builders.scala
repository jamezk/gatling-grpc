package com.github.phisgr.gatling.grpc.request

import com.github.phisgr.gatling.grpc.action._
import com.github.phisgr.gatling.grpc.stream.StreamCall._
import com.github.phisgr.gatling.grpc.stream.{BidiStreamCall, ClientStreamCall, ServerStreamCall, StreamCall}
import com.github.phisgr.gatling.grpc.util.checkMethodDescriptor
import io.gatling.core.Predef.Session
import io.gatling.core.action.builder.ActionBuilder
import io.gatling.core.session.Expression
import io.grpc.MethodDescriptor

import java.io.{ByteArrayInputStream, InputStream}
import scala.reflect.ClassTag
import scala.util.Try

case class Grpc private[gatling](requestName: Expression[String]) {
  def rpc[Req, Res](method: MethodDescriptor[Req, Res]): Unary[Req, Res] =
    new Unary(requestName, method)

  /**
   * Allows us to create a call from just the service and method name
   * @param serviceName
   * @param methodName
   * @return
   */
  def rpcRaw(serviceName: String, methodName: String): UnaryRaw = {
    val methodDescriptor = MethodDescriptor.newBuilder[Array[Byte], Array[Byte]]()
                                           .setType(MethodDescriptor.MethodType.UNARY)
                                           .setFullMethodName(s"$serviceName/$methodName")
                                           .setRequestMarshaller(RawMessageMarshaller)
                                           .setResponseMarshaller(RawMessageMarshaller)
                                           .build

    UnaryRaw(requestName, methodDescriptor)
  }

  def serverStream[Req, Res](method: MethodDescriptor[Req, Res], streamName: Expression[String]): ServerStream[Req, Res] =
    ServerStream(requestName, method, streamName)

  def bidiStream[Req: ClassTag, Res](method: MethodDescriptor[Req, Res], streamName: Expression[String]): BidiStream[Req, Res] =
    BidiStream(requestName, method, streamName)

  def clientStream[Req: ClassTag, Res](method: MethodDescriptor[Req, Res], streamName: Expression[String]): ClientStream[Req, Res] =
    ClientStream(requestName, method, streamName)
}


object RawMessageMarshaller extends MethodDescriptor.Marshaller[Array[Byte]] {
  def stream(value: Array[Byte]) = new ByteArrayInputStream(value)

  def parse(stream: InputStream): Array[Byte] =
    Try(stream.readAllBytes).getOrElse(throw new RuntimeException("Failed to read bytes"))
}

class Unary[Req, Res] private[gatling](requestName: Expression[String], method: MethodDescriptor[Req, Res]) {
  checkMethodDescriptor(method, expected = MethodDescriptor.MethodType.UNARY)

  def payload(req: Expression[Req]): GrpcCallActionBuilder[Req, Res] =
    GrpcCallActionBuilder(requestName, method, req)
}


case class UnaryRaw private[gatling](
                                      requestName: Expression[String],
                                      method: MethodDescriptor[Array[Byte], Array[Byte]]
                                    ) {
  checkMethodDescriptor(method, expected = MethodDescriptor.MethodType.UNARY)

  def payload(req: Expression[Array[Byte]]): GrpcCallActionBuilder[Array[Byte], Array[Byte]] =
    GrpcCallActionBuilder(requestName, method, req)

  // Rename the method to make it distinct
  def payloadFromProto[T <: com.google.protobuf.Message](message: Expression[T]): GrpcCallActionBuilder[Array[Byte], Array[Byte]] = {
    val byteArrayExpr: Expression[Array[Byte]] = message.map(_.toByteArray)
    payload(byteArrayExpr)
  }
}

sealed abstract class ListeningStream[Status >: Completed, C <: StreamCall[_, _, Status] : ClassTag] private[gatling] {
  private[this] val callClass: Class[C] = implicitly[ClassTag[C]].runtimeClass.asInstanceOf[Class[C]]

  val requestName: Expression[String]
  val streamName: Expression[String]
  def direction: String

  def cancelStream = new StreamCancelBuilder(requestName, streamName, direction)

  /**
   * Combines the streaming state and the main flow using [[StreamStartBuilder.sessionCombiner]].
   * If the stream has already ended, the streaming call will be removed from the session.
   *
   * @param waitFor Defaults to [[NoWait]]. Can wait for [[NextMessage]] or [[StreamEnd]] before combining.
   * @param sync    If true, the streaming state will also be updated.
   * @return an [[ActionBuilder]]
   */
  def reconciliate(waitFor: WaitType = NoWait, sync: Boolean = false): ActionBuilder =
    new StreamReconciliateBuilder(requestName, streamName, direction, waitFor, sync)

  // Keep the nice looking no-parenthesis call syntax
  def reconciliate: ActionBuilder = reconciliate()

  def status: Expression[Status] = { session: Session =>
    StreamMessageAction.fetchCall(callClass, streamName, session, direction).map(_.state)
  }
}

case class ServerStream[Req, Res] private[gatling](
                                                    requestName: Expression[String],
                                                    method: MethodDescriptor[Req, Res],
                                                    streamName: Expression[String]
                                                  ) extends ListeningStream[ServerStreamState, ServerStreamCall[_, _]] {
  checkMethodDescriptor(method, expected = MethodDescriptor.MethodType.SERVER_STREAMING)

  override def direction: String = "server"

  def start(req: Expression[Req]): ServerStreamStartActionBuilder[Req, Res] = {
    ServerStreamStartActionBuilder(requestName, streamName, method, req)
  }
}

case class BidiStream[Req: ClassTag, Res] private[gatling](
                                                            requestName: Expression[String],
                                                            method: MethodDescriptor[Req, Res],
                                                            streamName: Expression[String]
                                                          ) extends ListeningStream[BidiStreamState, BidiStreamCall[_, _]] {
  checkMethodDescriptor(method, expected = MethodDescriptor.MethodType.BIDI_STREAMING)

  override def direction: String = "bidi"

  def connect: BidiStreamStartActionBuilder[Req, Res] = {
    BidiStreamStartActionBuilder(requestName, streamName, method)
  }

  def send(req: Expression[Req]) = StreamSendBuilder(requestName, streamName, req, direction = direction)
  def complete(waitFor: WaitType = NoWait) = new StreamCompleteBuilder(requestName, streamName, waitFor)

  // Keep the nice looking no-parenthesis call syntax
  def complete: StreamCompleteBuilder = complete()
}

case class ClientStream[Req: ClassTag, Res] private[gatling](
                                                              requestName: Expression[String],
                                                              method: MethodDescriptor[Req, Res],
                                                              streamName: Expression[String]
                                                            ) {
  checkMethodDescriptor(method, expected = MethodDescriptor.MethodType.CLIENT_STREAMING)
  private def clientStreamDirection: String = "client"

  def connect: ClientStreamStartActionBuilder[Req, Res] = {
    ClientStreamStartActionBuilder(requestName, streamName, method)
  }

  def send(req: Expression[Req]) = new StreamSendBuilder(requestName, streamName, req, direction = clientStreamDirection)
  def cancelStream = new StreamCancelBuilder(requestName, streamName, direction = clientStreamDirection)
  /**
   * If server completes before client complete,
   * the measured time will be negative.
   * If this behaviour is not good enough for you,
   * please open an issue with your use case,
   * so that we can better design the API.
   */
  def completeAndWait = new ClientStreamCompletionBuilder(requestName, streamName)

  def status: Expression[ClientStreamState] = { session: Session =>
    StreamMessageAction.fetchCall(classOf[ClientStreamCall[_, _]], streamName, session, clientStreamDirection).map(_.state)
  }
}
