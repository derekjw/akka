/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import scala.concurrent.util.Duration
import akka.util.internal.{ TimerTask, HashedWheelTimer, Timeout ⇒ HWTimeout, Timer }
import akka.event.LoggingAdapter
import akka.dispatch.MessageDispatcher
import java.io.Closeable
import java.util.concurrent.atomic.{ AtomicReference, AtomicLong }
import scala.annotation.tailrec
import akka.util.internal._
import concurrent.ExecutionContext
import scala.concurrent.util.FiniteDuration

//#scheduler
/**
 * An Akka scheduler service. This one needs one special behavior: if
 * Closeable, it MUST execute all outstanding tasks upon .close() in order
 * to properly shutdown all dispatchers.
 *
 * Furthermore, this timer service MUST throw IllegalStateException if it
 * cannot schedule a task. Once scheduled, the task MUST be executed. If
 * executed upon close(), the task may execute before its timeout.
 */
trait Scheduler {
  /**
   * Schedules a message to be sent repeatedly with an initial delay and
   * frequency. E.g. if you would like a message to be sent immediately and
   * thereafter every 500ms you would set delay=Duration.Zero and
   * interval=Duration(500, TimeUnit.MILLISECONDS)
   *
   * Java & Scala API
   */
  def schedule(
    initialDelay: FiniteDuration,
    interval: FiniteDuration,
    receiver: ActorRef,
    message: Any)(implicit executor: ExecutionContext): Cancellable

  /**
   * Schedules a function to be run repeatedly with an initial delay and a
   * frequency. E.g. if you would like the function to be run after 2 seconds
   * and thereafter every 100ms you would set delay = Duration(2, TimeUnit.SECONDS)
   * and interval = Duration(100, TimeUnit.MILLISECONDS)
   *
   * Scala API
   */
  def schedule(
    initialDelay: FiniteDuration,
    interval: FiniteDuration)(f: ⇒ Unit)(implicit executor: ExecutionContext): Cancellable

  /**
   * Schedules a function to be run repeatedly with an initial delay and
   * a frequency. E.g. if you would like the function to be run after 2
   * seconds and thereafter every 100ms you would set delay = Duration(2,
   * TimeUnit.SECONDS) and interval = Duration(100, TimeUnit.MILLISECONDS)
   *
   * Java API
   */
  def schedule(
    initialDelay: FiniteDuration,
    interval: FiniteDuration,
    runnable: Runnable)(implicit executor: ExecutionContext): Cancellable

  /**
   * Schedules a Runnable to be run once with a delay, i.e. a time period that
   * has to pass before the runnable is executed.
   *
   * Java & Scala API
   */
  def scheduleOnce(
    delay: FiniteDuration,
    runnable: Runnable)(implicit executor: ExecutionContext): Cancellable

  /**
   * Schedules a message to be sent once with a delay, i.e. a time period that has
   * to pass before the message is sent.
   *
   * Java & Scala API
   */
  def scheduleOnce(
    delay: FiniteDuration,
    receiver: ActorRef,
    message: Any)(implicit executor: ExecutionContext): Cancellable

  /**
   * Schedules a function to be run once with a delay, i.e. a time period that has
   * to pass before the function is run.
   *
   * Scala API
   */
  def scheduleOnce(
    delay: FiniteDuration)(f: ⇒ Unit)(implicit executor: ExecutionContext): Cancellable
}
//#scheduler

//#cancellable
/**
 * Signifies something that can be cancelled
 * There is no strict guarantee that the implementation is thread-safe,
 * but it should be good practice to make it so.
 */
trait Cancellable {
  /**
   * Cancels this Cancellable
   *
   * Java & Scala API
   */
  def cancel(): Unit

  /**
   * Returns whether this Cancellable has been cancelled
   *
   * Java & Scala API
   */
  def isCancelled: Boolean
}
//#cancellable

/**
 * Scheduled tasks (Runnable and functions) are executed with the supplied dispatcher.
 * Note that dispatcher is by-name parameter, because dispatcher might not be initialized
 * when the scheduler is created.
 *
 * The HashedWheelTimer used by this class MUST throw an IllegalStateException
 * if it does not enqueue a task. Once a task is queued, it MUST be executed or
 * returned from stop().
 */
class DefaultScheduler(hashedWheelTimer: HashedWheelTimer, log: LoggingAdapter) extends Scheduler with Closeable {
  override def schedule(initialDelay: FiniteDuration,
                        delay: FiniteDuration,
                        receiver: ActorRef,
                        message: Any)(implicit executor: ExecutionContext): Cancellable = {
    val continuousCancellable = new ContinuousCancellable
    continuousCancellable.init(
      hashedWheelTimer.newTimeout(
        new AtomicLong(System.nanoTime + initialDelay.toNanos) with TimerTask with ContinuousScheduling {
          def run(timeout: HWTimeout) {
            executor execute new Runnable {
              override def run = {
                receiver ! message
                // Check if the receiver is still alive and kicking before reschedule the task
                if (receiver.isTerminated) log.debug("Could not reschedule message to be sent because receiving actor {} has been terminated.", receiver)
                else {
                  val driftNanos = System.nanoTime - getAndAdd(delay.toNanos)
                  scheduleNext(timeout, Duration.fromNanos(Math.max(delay.toNanos - driftNanos, 1)), continuousCancellable)
                }
              }
            }
          }
        },
        initialDelay))
  }

  override def schedule(initialDelay: FiniteDuration,
                        delay: FiniteDuration)(f: ⇒ Unit)(implicit executor: ExecutionContext): Cancellable =
    schedule(initialDelay, delay, new Runnable { override def run = f })

  override def schedule(initialDelay: FiniteDuration,
                        delay: FiniteDuration,
                        runnable: Runnable)(implicit executor: ExecutionContext): Cancellable = {
    val continuousCancellable = new ContinuousCancellable
    continuousCancellable.init(
      hashedWheelTimer.newTimeout(
        new AtomicLong(System.nanoTime + initialDelay.toNanos) with TimerTask with ContinuousScheduling {
          override def run(timeout: HWTimeout): Unit = executor.execute(new Runnable {
            override def run = {
              runnable.run()
              val driftNanos = System.nanoTime - getAndAdd(delay.toNanos)
              scheduleNext(timeout, Duration.fromNanos(Math.max(delay.toNanos - driftNanos, 1)), continuousCancellable)
            }
          })
        },
        initialDelay))
  }

  override def scheduleOnce(delay: FiniteDuration, runnable: Runnable)(implicit executor: ExecutionContext): Cancellable =
    new DefaultCancellable(
      hashedWheelTimer.newTimeout(
        new TimerTask() { def run(timeout: HWTimeout): Unit = executor.execute(runnable) },
        delay))

  override def scheduleOnce(delay: FiniteDuration, receiver: ActorRef, message: Any)(implicit executor: ExecutionContext): Cancellable =
    scheduleOnce(delay, new Runnable { override def run = receiver ! message })

  override def scheduleOnce(delay: FiniteDuration)(f: ⇒ Unit)(implicit executor: ExecutionContext): Cancellable =
    scheduleOnce(delay, new Runnable { override def run = f })

  private trait ContinuousScheduling { this: TimerTask ⇒
    def scheduleNext(timeout: HWTimeout, delay: FiniteDuration, delegator: ContinuousCancellable) {
      try delegator.swap(timeout.getTimer.newTimeout(this, delay)) catch { case _: IllegalStateException ⇒ } // stop recurring if timer is stopped
    }
  }

  private def execDirectly(t: HWTimeout): Unit = {
    try t.getTask.run(t) catch {
      case e: InterruptedException ⇒ throw e
      case e: Exception            ⇒ log.error(e, "exception while executing timer task")
    }
  }

  override def close(): Unit = {
    import scala.collection.JavaConverters._
    hashedWheelTimer.stop().asScala foreach execDirectly
  }
}

private[akka] object ContinuousCancellable {
  val initial: HWTimeout = new HWTimeout {
    override def getTimer: Timer = null
    override def getTask: TimerTask = null
    override def isExpired: Boolean = false
    override def isCancelled: Boolean = false
    override def cancel: Unit = ()
  }

  val cancelled: HWTimeout = new HWTimeout {
    override def getTimer: Timer = null
    override def getTask: TimerTask = null
    override def isExpired: Boolean = false
    override def isCancelled: Boolean = true
    override def cancel: Unit = ()
  }
}
/**
 * Wrapper of a [[org.jboss.netty.akka.util.Timeout]] that delegates all
 * methods. Needed to be able to cancel continuous tasks,
 * since they create new Timeout for each tick.
 */
private[akka] class ContinuousCancellable extends AtomicReference[HWTimeout](ContinuousCancellable.initial) with Cancellable {
  private[akka] def init(initialTimeout: HWTimeout): this.type = {
    compareAndSet(ContinuousCancellable.initial, initialTimeout)
    this
  }

  @tailrec private[akka] final def swap(newTimeout: HWTimeout): Unit = get match {
    case some if some.isCancelled ⇒ try cancel() finally newTimeout.cancel()
    case some                     ⇒ if (!compareAndSet(some, newTimeout)) swap(newTimeout)
  }

  def isCancelled(): Boolean = get().isCancelled()
  def cancel(): Unit = getAndSet(ContinuousCancellable.cancelled).cancel()
}

private[akka] class DefaultCancellable(timeout: HWTimeout) extends AtomicReference[HWTimeout](timeout) with Cancellable {
  override def cancel(): Unit = getAndSet(ContinuousCancellable.cancelled).cancel()
  override def isCancelled: Boolean = get().isCancelled
}
