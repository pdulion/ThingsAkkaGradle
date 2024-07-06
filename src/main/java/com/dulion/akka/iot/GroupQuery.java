package com.dulion.akka.iot;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.TimerScheduler;
import com.dulion.akka.iot.Device.ReadTemperatureReply;
import com.dulion.akka.iot.Manager.AllTemperaturesReply;
import com.dulion.akka.iot.Manager.DeviceTimedOut;
import com.dulion.akka.iot.Manager.Temperature;
import com.dulion.akka.iot.Manager.TemperatureNotAvailable;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import lombok.Value;

public class GroupQuery extends AbstractBehavior<GroupQuery.Request> {

  public interface Request {}

  private enum CollectionTimeout implements Request {
    INSTANCE
  }

  @Value
  private static class TemperatureReplyWrapper implements Request {
    ReadTemperatureReply reply;
  }

  @Value
  private static class DeviceTerminated implements Request {
    String deviceId;
  }

  /**
   * Create behavior for device group query.
   *
   * @param requestId       - tracking identifier.
   * @param replyTo         - where to send the results.
   * @param deviceIdToActor - map collection of devices to query.
   * @param timeout         - maximum time to wait for temperatures replies.
   * @return Behavior - reference to group query supplier.
   */
  public static Behavior<Request> create(
      long requestId,
      ActorRef<Manager.AllTemperaturesReply> replyTo,
      Map<String, ActorRef<Device.Request>> deviceIdToActor,
      Duration timeout) {
    return Behaviors.setup(
        context -> Behaviors.withTimers(
            timers -> new GroupQuery(
                requestId,
                replyTo,
                deviceIdToActor,
                timeout,
                context,
                timers)));
  }

  private final long requestId;
  private final ActorRef<AllTemperaturesReply> replyTo;
  private final HashSet<String> waiting;
  private final Map<String, Manager.TemperatureReading> replies = new HashMap<>();

  private GroupQuery(
      long requestId,
      ActorRef<AllTemperaturesReply> replyTo,
      Map<String, ActorRef<Device.Request>> deviceIdToActor,
      Duration timeout,
      ActorContext<Request> context,
      TimerScheduler<Request> timers) {
    super(context);
    this.requestId = requestId;
    this.replyTo = replyTo;

    timers.startSingleTimer(CollectionTimeout.INSTANCE, timeout);

    ActorRef<ReadTemperatureReply> adapter = context.messageAdapter(
        ReadTemperatureReply.class,
        TemperatureReplyWrapper::new);

    deviceIdToActor.entrySet().forEach(entry -> {
      context.watchWith(entry.getValue(), new DeviceTerminated(entry.getKey()));
      entry.getValue().tell(Device.ReadTemperatureRequest.builder()
          .requestId(requestId)
          .replyTo(adapter)
          .build());
    });
    waiting = new HashSet<>(deviceIdToActor.keySet());
  }

  @Override
  public Receive<Request> createReceive() {
    return newReceiveBuilder()
        .onMessage(TemperatureReplyWrapper.class, this::onTemperatureReply)
        .onMessage(DeviceTerminated.class, this::onDeviceTerminated)
        .onMessage(CollectionTimeout.class, this::onCollectionTimeout)
        .build();
  }

  private Behavior<Request> onTemperatureReply(TemperatureReplyWrapper wrapper) {
    ReadTemperatureReply reply = wrapper.getReply();
    Manager.TemperatureReading reading = Optional.ofNullable(reply.getTemperature())
        .map(t -> ((Manager.TemperatureReading) Temperature.builder().value(t).build()))
        .orElse(TemperatureNotAvailable.INSTANCE);
    replies.put(reply.getDeviceId(), reading);
    waiting.remove(reply.getDeviceId());
    return respondWhenCollected();
  }

  private Behavior<Request> onDeviceTerminated(DeviceTerminated terminated) {
    String deviceId = terminated.getDeviceId();
    if (waiting.remove(deviceId)) {
      replies.put(deviceId, DeviceTimedOut.INSTANCE);
    }
    return respondWhenCollected();
  }

  private Behavior<Request> onCollectionTimeout(CollectionTimeout timeout) {
    waiting.forEach(deviceId -> replies.put(deviceId, DeviceTimedOut.INSTANCE));
    waiting.clear();
    return respondWhenCollected();
  }

  private Behavior<Request> respondWhenCollected() {
    if (!waiting.isEmpty()) {
      return this;
    }

    replyTo.tell(Manager.AllTemperaturesReply.builder()
        .requestId(requestId)
        .temperatures(replies)
        .build());
    return Behaviors.stopped();
  }
}
