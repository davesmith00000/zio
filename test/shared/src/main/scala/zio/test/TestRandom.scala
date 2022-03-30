/*
 * Copyright 2019-2022 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.test

import zio._
import zio.internal.stacktracer.Tracer
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.util.UUID
import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.math.{log, sqrt}

/**
 * `TestRandom` allows for deterministically testing effects involving
 * randomness.
 *
 * `TestRandom` operates in two modes. In the first mode, `TestRandom` is a
 * purely functional pseudo-random number generator. It will generate
 * pseudo-random values just like `scala.util.Random` except that no internal
 * state is mutated. Instead, methods like `nextInt` describe state transitions
 * from one random state to another that are automatically composed together
 * through methods like `flatMap`. The random seed can be set using `setSeed`
 * and `TestRandom` is guaranteed to return the same sequence of values for any
 * given seed. This is useful for deterministically generating a sequence of
 * pseudo-random values and powers the property based testing functionality in
 * ZIO Test.
 *
 * In the second mode, `TestRandom` maintains an internal buffer of values that
 * can be "fed" with methods such as `feedInts` and then when random values of
 * that type are generated they will first be taken from the buffer. This is
 * useful for verifying that functions produce the expected output for a given
 * sequence of "random" inputs.
 *
 * {{{
 * import zio.Random
 * import zio.test.TestRandom
 *
 * for {
 *   _ <- TestRandom.feedInts(4, 5, 2)
 *   x <- Random.nextIntBounded(6)
 *   y <- Random.nextIntBounded(6)
 *   z <- Random.nextIntBounded(6)
 * } yield x + y + z == 11
 * }}}
 *
 * `TestRandom` will automatically take values from the buffer if a value of the
 * appropriate type is available and otherwise generate a pseudo-random value,
 * so there is nothing you need to do to switch between the two modes. Just
 * generate random values as you normally would to get pseudo-random values, or
 * feed in values of your own to get those values back. You can also use methods
 * like `clearInts` to clear the buffer of values of a given type so you can
 * fill the buffer with new values or go back to pseudo-random number
 * generation.
 */
trait TestRandom extends Random with Restorable {
  def clearBooleans(implicit trace: ZTraceElement): UIO[Unit]
  def clearBytes(implicit trace: ZTraceElement): UIO[Unit]
  def clearChars(implicit trace: ZTraceElement): UIO[Unit]
  def clearDoubles(implicit trace: ZTraceElement): UIO[Unit]
  def clearFloats(implicit trace: ZTraceElement): UIO[Unit]
  def clearInts(implicit trace: ZTraceElement): UIO[Unit]
  def clearLongs(implicit trace: ZTraceElement): UIO[Unit]
  def clearStrings(implicit trace: ZTraceElement): UIO[Unit]
  def clearUUIDs(implicit trace: ZTraceElement): UIO[Unit]
  def feedBooleans(booleans: Boolean*)(implicit trace: ZTraceElement): UIO[Unit]
  def feedBytes(bytes: Chunk[Byte]*)(implicit trace: ZTraceElement): UIO[Unit]
  def feedChars(chars: Char*)(implicit trace: ZTraceElement): UIO[Unit]
  def feedDoubles(doubles: Double*)(implicit trace: ZTraceElement): UIO[Unit]
  def feedFloats(floats: Float*)(implicit trace: ZTraceElement): UIO[Unit]
  def feedInts(ints: Int*)(implicit trace: ZTraceElement): UIO[Unit]
  def feedLongs(longs: Long*)(implicit trace: ZTraceElement): UIO[Unit]
  def feedStrings(strings: String*)(implicit trace: ZTraceElement): UIO[Unit]
  def feedUUIDs(UUIDs: UUID*)(implicit trace: ZTraceElement): UIO[Unit]
  def getSeed(implicit trace: ZTraceElement): UIO[Long]
  def setSeed(seed: => Long)(implicit trace: ZTraceElement): UIO[Unit]
}

object TestRandom extends Serializable {

  /**
   * Adapted from @gzmo work in Scala.js
   * (https://github.com/scala-js/scala-js/pull/780)
   */
  final case class Test(randomState: Ref.Atomic[Data], bufferState: Ref.Atomic[Buffer]) extends Random with TestRandom {

    /**
     * Clears the buffer of booleans.
     */
    def clearBooleans(implicit trace: ZTraceElement): UIO[Unit] =
      bufferState.update(_.copy(booleans = List.empty))

    /**
     * Clears the buffer of bytes.
     */
    def clearBytes(implicit trace: ZTraceElement): UIO[Unit] =
      bufferState.update(_.copy(bytes = List.empty))

    /**
     * Clears the buffer of characters.
     */
    def clearChars(implicit trace: ZTraceElement): UIO[Unit] =
      bufferState.update(_.copy(chars = List.empty))

    /**
     * Clears the buffer of doubles.
     */
    def clearDoubles(implicit trace: ZTraceElement): UIO[Unit] =
      bufferState.update(_.copy(doubles = List.empty))

    /**
     * Clears the buffer of floats.
     */
    def clearFloats(implicit trace: ZTraceElement): UIO[Unit] =
      bufferState.update(_.copy(floats = List.empty))

    /**
     * Clears the buffer of integers.
     */
    def clearInts(implicit trace: ZTraceElement): UIO[Unit] =
      bufferState.update(_.copy(integers = List.empty))

    /**
     * Clears the buffer of longs.
     */
    def clearLongs(implicit trace: ZTraceElement): UIO[Unit] =
      bufferState.update(_.copy(longs = List.empty))

    /**
     * Clears the buffer of strings.
     */
    def clearStrings(implicit trace: ZTraceElement): UIO[Unit] =
      bufferState.update(_.copy(strings = List.empty))

    /**
     * Clears the buffer of UUIDs.
     */
    def clearUUIDs(implicit trace: ZTraceElement): UIO[Unit] =
      bufferState.update(_.copy(uuids = List.empty))

    /**
     * Feeds the buffer with specified sequence of booleans. The first value in
     * the sequence will be the first to be taken. These values will be taken
     * before any values that were previously in the buffer.
     */
    def feedBooleans(booleans: Boolean*)(implicit trace: ZTraceElement): UIO[Unit] =
      bufferState.update(data => data.copy(booleans = booleans.toList ::: data.booleans))

    /**
     * Feeds the buffer with specified sequence of chunks of bytes. The first
     * value in the sequence will be the first to be taken. These values will be
     * taken before any values that were previously in the buffer.
     */
    def feedBytes(bytes: Chunk[Byte]*)(implicit trace: ZTraceElement): UIO[Unit] =
      bufferState.update(data => data.copy(bytes = bytes.toList ::: data.bytes))

    /**
     * Feeds the buffer with specified sequence of characters. The first value
     * in the sequence will be the first to be taken. These values will be taken
     * before any values that were previously in the buffer.
     */
    def feedChars(chars: Char*)(implicit trace: ZTraceElement): UIO[Unit] =
      bufferState.update(data => data.copy(chars = chars.toList ::: data.chars))

    /**
     * Feeds the buffer with specified sequence of doubles. The first value in
     * the sequence will be the first to be taken. These values will be taken
     * before any values that were previously in the buffer.
     */
    def feedDoubles(doubles: Double*)(implicit trace: ZTraceElement): UIO[Unit] =
      bufferState.update(data => data.copy(doubles = doubles.toList ::: data.doubles))

    /**
     * Feeds the buffer with specified sequence of floats. The first value in
     * the sequence will be the first to be taken. These values will be taken
     * before any values that were previously in the buffer.
     */
    def feedFloats(floats: Float*)(implicit trace: ZTraceElement): UIO[Unit] =
      bufferState.update(data => data.copy(floats = floats.toList ::: data.floats))

    /**
     * Feeds the buffer with specified sequence of integers. The first value in
     * the sequence will be the first to be taken. These values will be taken
     * before any values that were previously in the buffer.
     */
    def feedInts(ints: Int*)(implicit trace: ZTraceElement): UIO[Unit] =
      bufferState.update(data => data.copy(integers = ints.toList ::: data.integers))

    /**
     * Feeds the buffer with specified sequence of longs. The first value in the
     * sequence will be the first to be taken. These values will be taken before
     * any values that were previously in the buffer.
     */
    def feedLongs(longs: Long*)(implicit trace: ZTraceElement): UIO[Unit] =
      bufferState.update(data => data.copy(longs = longs.toList ::: data.longs))

    /**
     * Feeds the buffer with specified sequence of strings. The first value in
     * the sequence will be the first to be taken. These values will be taken
     * before any values that were previously in the buffer.
     */
    def feedStrings(strings: String*)(implicit trace: ZTraceElement): UIO[Unit] =
      bufferState.update(data => data.copy(strings = strings.toList ::: data.strings))

    /**
     * Feeds the buffer with specified sequence of UUIDs. The first value in the
     * sequence will be the first to be taken. These values will be taken before
     * any values that were previously in the buffer.
     */
    def feedUUIDs(uuids: UUID*)(implicit trace: ZTraceElement): UIO[Unit] =
      bufferState.update(data => data.copy(uuids = uuids.toList ::: data.uuids))

    /**
     * Gets the seed of this `TestRandom`.
     */
    def getSeed(implicit trace: ZTraceElement): UIO[Long] =
      randomState.get.map { case Data(seed1, seed2, _) =>
        ((seed1.toLong << 24) | seed2) ^ 0x5deece66dL
      }

    /**
     * Takes a boolean from the buffer if one exists or else generates a
     * pseudo-random boolean.
     */
    def nextBoolean(implicit trace: ZTraceElement): UIO[Boolean] =
      ZIO.succeed(unsafeNextBoolean())

    /**
     * Takes a chunk of bytes from the buffer if one exists or else generates a
     * pseudo-random chunk of bytes of the specified length.
     */
    def nextBytes(length: => Int)(implicit trace: ZTraceElement): UIO[Chunk[Byte]] =
      ZIO.succeed(unsafeNextBytes(length))

    /**
     * Takes a double from the buffer if one exists or else generates a
     * pseudo-random, uniformly distributed double between 0.0 and 1.0.
     */
    def nextDouble(implicit trace: ZTraceElement): UIO[Double] =
      ZIO.succeed(unsafeNextDouble())

    /**
     * Takes a double from the buffer if one exists or else generates a
     * pseudo-random double in the specified range.
     */
    def nextDoubleBetween(minInclusive: => Double, maxExclusive: => Double)(implicit
      trace: ZTraceElement
    ): UIO[Double] =
      ZIO.succeed(unsafeNextDoubleBetween(minInclusive, maxExclusive))

    /**
     * Takes a float from the buffer if one exists or else generates a
     * pseudo-random, uniformly distributed float between 0.0 and 1.0.
     */
    def nextFloat(implicit trace: ZTraceElement): UIO[Float] =
      ZIO.succeed(unsafeNextFloat())

    /**
     * Takes a float from the buffer if one exists or else generates a
     * pseudo-random float in the specified range.
     */
    def nextFloatBetween(minInclusive: => Float, maxExclusive: => Float)(implicit trace: ZTraceElement): UIO[Float] =
      ZIO.succeed(unsafeNextFloatBetween(minInclusive, maxExclusive))

    /**
     * Takes a double from the buffer if one exists or else generates a
     * pseudo-random double from a normal distribution with mean 0.0 and
     * standard deviation 1.0.
     */
    def nextGaussian(implicit trace: ZTraceElement): UIO[Double] =
      ZIO.succeed(unsafeNextGaussian())

    /**
     * Takes an integer from the buffer if one exists or else generates a
     * pseudo-random integer.
     */
    def nextInt(implicit trace: ZTraceElement): UIO[Int] =
      ZIO.succeed(unsafeNextInt())

    /**
     * Takes an integer from the buffer if one exists or else generates a
     * pseudo-random integer in the specified range.
     */
    def nextIntBetween(minInclusive: => Int, maxExclusive: => Int)(implicit trace: ZTraceElement): UIO[Int] =
      ZIO.succeed(unsafeNextIntBetween(minInclusive, maxExclusive))

    /**
     * Takes an integer from the buffer if one exists or else generates a
     * pseudo-random integer between 0 (inclusive) and the specified value
     * (exclusive).
     */
    def nextIntBounded(n: => Int)(implicit trace: ZTraceElement): UIO[Int] =
      ZIO.succeed(unsafeNextIntBounded(n))

    /**
     * Takes a long from the buffer if one exists or else generates a
     * pseudo-random long.
     */
    def nextLong(implicit trace: ZTraceElement): UIO[Long] =
      ZIO.succeed(unsafeNextLong())

    /**
     * Takes a long from the buffer if one exists or else generates a
     * pseudo-random long in the specified range.
     */
    def nextLongBetween(minInclusive: => Long, maxExclusive: => Long)(implicit trace: ZTraceElement): UIO[Long] =
      ZIO.succeed(unsafeNextLongBetween(minInclusive, maxExclusive))

    /**
     * Takes a long from the buffer if one exists or else generates a
     * pseudo-random long between 0 (inclusive) and the specified value
     * (exclusive).
     */
    def nextLongBounded(n: => Long)(implicit trace: ZTraceElement): UIO[Long] =
      ZIO.succeed(unsafeNextLongBounded(n))

    /**
     * Takes a character from the buffer if one exists or else generates a
     * pseudo-random character from the ASCII range 33-126.
     */
    def nextPrintableChar(implicit trace: ZTraceElement): UIO[Char] =
      ZIO.succeed(unsafeNextPrintableChar())

    /**
     * Takes a string from the buffer if one exists or else generates a
     * pseudo-random string of the specified length.
     */
    def nextString(length: => Int)(implicit trace: ZTraceElement): UIO[String] =
      ZIO.succeed(unsafeNextString(length))

    /**
     * Takes a UUID from the buffer if one exists or else generates a
     * pseudo-random UUID.
     */
    def nextUUID(implicit trace: ZTraceElement): UIO[UUID] =
      ZIO.succeed(unsafeNextUUID())

    /**
     * Saves the `TestRandom`'s current state in an effect which, when run, will
     * restore the `TestRandom` state to the saved state.
     */
    def save(implicit trace: ZTraceElement): UIO[UIO[Unit]] =
      for {
        randomData <- randomState.get
        bufferData <- bufferState.get
      } yield randomState.set(randomData) *> bufferState.set(bufferData)

    /**
     * Sets the seed of this `TestRandom` to the specified value.
     */
    def setSeed(seed: => Long)(implicit trace: ZTraceElement): UIO[Unit] =
      ZIO.succeed(unsafeSetSeed(seed))

    /**
     * Randomly shuffles the specified list.
     */
    def shuffle[A, Collection[+Element] <: Iterable[Element]](
      list: => Collection[A]
    )(implicit bf: BuildFrom[Collection[A], A, Collection[A]], trace: ZTraceElement): UIO[Collection[A]] =
      ZIO.succeed(unsafeShuffle(list))

    override private[zio] def unsafeNextBoolean(): Boolean =
      getOrElse(bufferedBoolean)(randomBoolean)

    override private[zio] def unsafeNextBytes(length: Int): Chunk[Byte] =
      getOrElse(bufferedBytes)(randomBytes(length))

    override private[zio] def unsafeNextDouble(): Double =
      getOrElse(bufferedDouble)(randomDouble)

    override private[zio] def unsafeNextDoubleBetween(minInclusive: Double, maxExclusive: Double): Double =
      getOrElse(bufferedDouble)(randomDoubleBetween(minInclusive, maxExclusive))

    override private[zio] def unsafeNextFloat(): Float =
      getOrElse(bufferedFloat)(randomFloat)

    override private[zio] def unsafeNextFloatBetween(minInclusive: Float, maxExclusive: Float): Float =
      getOrElse(bufferedFloat)(randomFloatBetween(minInclusive, maxExclusive))

    override private[zio] def unsafeNextGaussian(): Double =
      getOrElse(bufferedDouble)(randomGaussian)

    override private[zio] def unsafeNextInt(): Int =
      getOrElse(bufferedInt)(randomInt)

    override private[zio] def unsafeNextIntBetween(minInclusive: Int, maxExclusive: Int): Int =
      getOrElse(bufferedInt)(randomIntBetween(minInclusive, maxExclusive))

    override private[zio] def unsafeNextIntBounded(n: Int): Int =
      getOrElse(bufferedInt)(randomIntBounded(n))

    override private[zio] def unsafeNextLong(): Long =
      getOrElse(bufferedLong)(randomLong)

    override private[zio] def unsafeNextLongBetween(minInclusive: Long, maxExclusive: Long): Long =
      getOrElse(bufferedLong)(randomLongBetween(minInclusive, maxExclusive))

    override private[zio] def unsafeNextLongBounded(n: Long): Long =
      getOrElse(bufferedLong)(randomLongBounded(n))

    override private[zio] def unsafeNextPrintableChar(): Char =
      getOrElse(bufferedChar)(randomPrintableChar)

    override private[zio] def unsafeNextString(length: Int): String =
      getOrElse(bufferedString)(randomString(length))

    override private[zio] def unsafeNextUUID(): UUID =
      getOrElse(bufferedUUID)(Random.nextUUIDWith(() => unsafeNextLong()))

    override private[zio] def unsafeSetSeed(seed: Long): Unit =
      randomState.unsafeSet {
        val newSeed = (seed ^ 0x5deece66dL) & ((1L << 48) - 1)
        val seed1   = (newSeed >>> 24).toInt
        val seed2   = newSeed.toInt & ((1 << 24) - 1)
        Data(seed1, seed2, Queue.empty)
      }

    override private[zio] def unsafeShuffle[A, Collection[+Element] <: Iterable[Element]](collection: Collection[A])(
      implicit bf: BuildFrom[Collection[A], A, Collection[A]]
    ): Collection[A] =
      Random.shuffleWith(randomIntBounded, collection)

    private def bufferedBoolean(buffer: Buffer): (Option[Boolean], Buffer) =
      (
        buffer.booleans.headOption,
        buffer.copy(booleans = buffer.booleans.drop(1))
      )

    private def bufferedBytes(buffer: Buffer): (Option[Chunk[Byte]], Buffer) =
      (
        buffer.bytes.headOption,
        buffer.copy(bytes = buffer.bytes.drop(1))
      )

    private def bufferedChar(buffer: Buffer): (Option[Char], Buffer) =
      (
        buffer.chars.headOption,
        buffer.copy(chars = buffer.chars.drop(1))
      )

    private def bufferedDouble(buffer: Buffer): (Option[Double], Buffer) =
      (
        buffer.doubles.headOption,
        buffer.copy(doubles = buffer.doubles.drop(1))
      )

    private def bufferedFloat(buffer: Buffer): (Option[Float], Buffer) =
      (
        buffer.floats.headOption,
        buffer.copy(floats = buffer.floats.drop(1))
      )

    private def bufferedInt(buffer: Buffer): (Option[Int], Buffer) =
      (
        buffer.integers.headOption,
        buffer.copy(integers = buffer.integers.drop(1))
      )

    private def bufferedLong(buffer: Buffer): (Option[Long], Buffer) =
      (
        buffer.longs.headOption,
        buffer.copy(longs = buffer.longs.drop(1))
      )

    private def bufferedString(buffer: Buffer): (Option[String], Buffer) =
      (
        buffer.strings.headOption,
        buffer.copy(strings = buffer.strings.drop(1))
      )

    private def bufferedUUID(buffer: Buffer): (Option[UUID], Buffer) =
      (
        buffer.uuids.headOption,
        buffer.copy(uuids = buffer.uuids.drop(1))
      )

    private def getOrElse[A](buffer: Buffer => (Option[A], Buffer))(random: => A): A =
      bufferState.unsafeModify(buffer).getOrElse(random)

    @inline
    private def leastSignificantBits(x: Double): Int =
      toInt(x) & ((1 << 24) - 1)

    @inline
    private def mostSignificantBits(x: Double): Int =
      toInt(x / (1 << 24).toDouble)

    private def randomBits(bits: Int): Int =
      randomState.unsafeModify { data =>
        val multiplier  = 0x5deece66dL
        val multiplier1 = (multiplier >>> 24).toInt
        val multiplier2 = multiplier.toInt & ((1 << 24) - 1)
        val product1    = data.seed2.toDouble * multiplier1.toDouble + data.seed1.toDouble * multiplier2.toDouble
        val product2    = data.seed2.toDouble * multiplier2.toDouble + 0xb
        val newSeed1    = (mostSignificantBits(product2) + leastSignificantBits(product1)) & ((1 << 24) - 1)
        val newSeed2    = leastSignificantBits(product2)
        val result      = (newSeed1 << 8) | (newSeed2 >> 16)
        (result >>> (32 - bits), Data(newSeed1, newSeed2, data.nextNextGaussians))
      }

    private def randomBoolean: Boolean =
      randomBits(1) != 0

    private def randomBytes(length: Int): Chunk[Byte] = {
      //  Our RNG generates 32 bit integers so to maximize efficiency we want to
      //  pull 8 bit bytes from the current integer until it is exhausted
      //  before generating another random integer
      @tailrec
      def loop(i: Int, rnd: () => Int, n: Int, acc: List[Byte]): List[Byte] =
        if (i == length)
          acc.reverse
        else if (n > 0) {
          val r = rnd()
          loop(i + 1, () => r >> 8, n - 1, r.toByte :: acc)
        } else
          loop(i, () => randomInt, (length - i) min 4, acc)

      Chunk.fromIterable(loop(0, () => randomInt, length min 4, List.empty))
    }

    private def randomDouble: Double = {
      val i1 = randomBits(26)
      val i2 = randomBits(27)
      ((i1.toDouble * (1L << 27).toDouble) + i2.toDouble) / (1L << 53).toDouble
    }

    private def randomDoubleBetween(minInclusive: Double, maxExclusive: Double): Double =
      Random.nextDoubleBetweenWith(minInclusive, maxExclusive)(() => randomDouble)

    private def randomFloat: Float =
      (randomBits(24).toDouble / (1 << 24).toDouble).toFloat

    private def randomFloatBetween(minInclusive: Float, maxExclusive: Float): Float =
      Random.nextFloatBetweenWith(minInclusive, maxExclusive)(() => randomFloat)

    private def randomGaussian: Double =
      //  The Box-Muller transform generates two normally distributed random
      //  doubles, so we store the second double in a queue and check the
      //  queue before computing a new pair of values to avoid wasted work.
      randomState.unsafeModify { case Data(seed1, seed2, queue) =>
        queue.dequeueOption.fold((Option.empty[Double], Data(seed1, seed2, queue))) { case (d, queue) =>
          (Some(d), Data(seed1, seed2, queue))
        }
      } match {
        case Some(nextNextGaussian) => nextNextGaussian
        case None =>
          @tailrec
          def loop: (Double, Double, Double) = {
            val d1     = randomDouble
            val d2     = randomDouble
            val x      = 2 * d1 - 1
            val y      = 2 * d2 - 1
            val radius = x * x + y * y
            if (radius >= 1 || radius == 0) loop else (x, y, radius)
          }
          loop match {
            case (x, y, radius) =>
              val c = sqrt(-2 * log(radius) / radius)
              randomState.unsafeModify { case Data(seed1, seed2, queue) =>
                (x * c, Data(seed1, seed2, queue.enqueue(y * c)))
              }
          }
      }

    private def randomInt: Int =
      randomBits(32)

    private def randomIntBounded(n: Int): Int =
      if (n <= 0)
        throw new IllegalArgumentException("n must be positive")
      else if ((n & -n) == n)
        randomBits(31) >> Integer.numberOfLeadingZeros(n)
      else {
        @tailrec
        def loop: Int = {
          val i     = randomBits(31)
          val value = i % n
          if (i - value + (n - 1) < 0) loop
          else value
        }
        loop
      }

    private def randomIntBetween(minInclusive: Int, maxExclusive: Int): Int =
      Random.nextIntBetweenWith(minInclusive, maxExclusive)(() => randomInt, randomIntBounded)

    private def randomLong: Long = {
      val i1 = randomBits(32)
      val i2 = randomBits(32)
      (i1.toLong << 32) + i2
    }

    private def randomLongBounded(n: Long): Long =
      Random.nextLongBoundedWith(n)(() => randomLong)

    private def randomLongBetween(minInclusive: Long, maxExclusive: Long): Long =
      Random.nextLongBetweenWith(minInclusive, maxExclusive)(() => randomLong, randomLongBounded)

    private def randomPrintableChar: Char =
      (randomIntBounded(127 - 33) + 33).toChar

    private def randomString(length: Int): String =
      List.fill(length)((randomIntBounded(0xd800 - 1) + 1).toChar).mkString

    @inline
    private def toInt(x: Double): Int =
      (x.asInstanceOf[Long] | 0.asInstanceOf[Long]).asInstanceOf[Int]
  }

  /**
   * An arbitrary initial seed for the `TestRandom`.
   */
  val DefaultData: Data = Data(1071905196, 1911589680)

  /**
   * The seed of the `TestRandom`.
   */
  final case class Data(
    seed1: Int,
    seed2: Int,
    private[TestRandom] val nextNextGaussians: Queue[Double] = Queue.empty
  )

  /**
   * Accesses a `TestRandom` instance in the environment and clears the buffer
   * of booleans.
   */
  def clearBooleans(implicit trace: ZTraceElement): URIO[TestRandom, Unit] =
    ZIO.serviceWithZIO(_.clearBooleans)

  /**
   * Accesses a `TestRandom` instance in the environment and clears the buffer
   * of bytes.
   */
  def clearBytes(implicit trace: ZTraceElement): URIO[TestRandom, Unit] =
    ZIO.serviceWithZIO(_.clearBytes)

  /**
   * Accesses a `TestRandom` instance in the environment and clears the buffer
   * of characters.
   */
  def clearChars(implicit trace: ZTraceElement): URIO[TestRandom, Unit] =
    ZIO.serviceWithZIO(_.clearChars)

  /**
   * Accesses a `TestRandom` instance in the environment and clears the buffer
   * of doubles.
   */
  def clearDoubles(implicit trace: ZTraceElement): URIO[TestRandom, Unit] =
    ZIO.serviceWithZIO(_.clearDoubles)

  /**
   * Accesses a `TestRandom` instance in the environment and clears the buffer
   * of floats.
   */
  def clearFloats(implicit trace: ZTraceElement): URIO[TestRandom, Unit] =
    ZIO.serviceWithZIO(_.clearFloats)

  /**
   * Accesses a `TestRandom` instance in the environment and clears the buffer
   * of integers.
   */
  def clearInts(implicit trace: ZTraceElement): URIO[TestRandom, Unit] =
    ZIO.serviceWithZIO(_.clearInts)

  /**
   * Accesses a `TestRandom` instance in the environment and clears the buffer
   * of longs.
   */
  def clearLongs(implicit trace: ZTraceElement): URIO[TestRandom, Unit] =
    ZIO.serviceWithZIO(_.clearLongs)

  /**
   * Accesses a `TestRandom` instance in the environment and clears the buffer
   * of strings.
   */
  def clearStrings(implicit trace: ZTraceElement): URIO[TestRandom, Unit] =
    ZIO.serviceWithZIO(_.clearStrings)

  /**
   * Accesses a `TestRandom` instance in the environment and clears the buffer
   * of UUIDs.
   */
  def clearUUIDs(implicit trace: ZTraceElement): URIO[TestRandom, Unit] =
    ZIO.serviceWithZIO(_.clearUUIDs)

  /**
   * Accesses a `TestRandom` instance in the environment and feeds the buffer
   * with the specified sequence of booleans.
   */
  def feedBooleans(booleans: Boolean*)(implicit trace: ZTraceElement): URIO[TestRandom, Unit] =
    ZIO.serviceWithZIO(_.feedBooleans(booleans: _*))

  /**
   * Accesses a `TestRandom` instance in the environment and feeds the buffer
   * with the specified sequence of chunks of bytes.
   */
  def feedBytes(bytes: Chunk[Byte]*)(implicit trace: ZTraceElement): URIO[TestRandom, Unit] =
    ZIO.serviceWithZIO(_.feedBytes(bytes: _*))

  /**
   * Accesses a `TestRandom` instance in the environment and feeds the buffer
   * with the specified sequence of characters.
   */
  def feedChars(chars: Char*)(implicit trace: ZTraceElement): URIO[TestRandom, Unit] =
    ZIO.serviceWithZIO(_.feedChars(chars: _*))

  /**
   * Accesses a `TestRandom` instance in the environment and feeds the buffer
   * with the specified sequence of doubles.
   */
  def feedDoubles(doubles: Double*)(implicit trace: ZTraceElement): URIO[TestRandom, Unit] =
    ZIO.serviceWithZIO(_.feedDoubles(doubles: _*))

  /**
   * Accesses a `TestRandom` instance in the environment and feeds the buffer
   * with the specified sequence of floats.
   */
  def feedFloats(floats: Float*)(implicit trace: ZTraceElement): URIO[TestRandom, Unit] =
    ZIO.serviceWithZIO(_.feedFloats(floats: _*))

  /**
   * Accesses a `TestRandom` instance in the environment and feeds the buffer
   * with the specified sequence of integers.
   */
  def feedInts(ints: Int*)(implicit trace: ZTraceElement): URIO[TestRandom, Unit] =
    ZIO.serviceWithZIO(_.feedInts(ints: _*))

  /**
   * Accesses a `TestRandom` instance in the environment and feeds the buffer
   * with the specified sequence of longs.
   */
  def feedLongs(longs: Long*)(implicit trace: ZTraceElement): URIO[TestRandom, Unit] =
    ZIO.serviceWithZIO(_.feedLongs(longs: _*))

  /**
   * Accesses a `TestRandom` instance in the environment and feeds the buffer
   * with the specified sequence of strings.
   */
  def feedStrings(strings: String*)(implicit trace: ZTraceElement): URIO[TestRandom, Unit] =
    ZIO.serviceWithZIO(_.feedStrings(strings: _*))

  /**
   * Accesses a `TestRandom` instance in the environment and feeds the buffer
   * with the specified sequence of UUIDs.
   */
  def feedUUIDs(uuids: UUID*)(implicit trace: ZTraceElement): URIO[TestRandom, Unit] =
    ZIO.serviceWithZIO(_.feedUUIDs(uuids: _*))

  /**
   * Accesses a `TestRandom` instance in the environment and gets the seed.
   */
  def getSeed(implicit trace: ZTraceElement): URIO[TestRandom, Long] =
    ZIO.serviceWithZIO(_.getSeed)

  /**
   * Constructs a new `TestRandom` with the specified initial state. This can be
   * useful for providing the required environment to an effect that requires a
   * `Random`, such as with `ZIO#provide`.
   */
  def make(data: Data): Layer[Nothing, TestRandom] = {
    implicit val trace = Tracer.newTrace
    ZLayer {
      for {
        data   <- ZIO.succeed(Ref.unsafeMake(data))
        buffer <- ZIO.succeed(Ref.unsafeMake(Buffer()))
        test    = Test(data, buffer)
      } yield test
    }
  }

  val any: ZLayer[TestRandom, Nothing, TestRandom] =
    ZLayer.environment[TestRandom](Tracer.newTrace)

  val deterministic: Layer[Nothing, TestRandom] =
    make(DefaultData)

  val random: ZLayer[Clock, Nothing, TestRandom] = {
    implicit val trace = Tracer.newTrace
    (ZLayer.service[Clock] ++ deterministic) >>> ZLayer {
      for {
        random     <- ZIO.service[Random]
        testRandom <- ZIO.service[TestRandom]
        time       <- Clock.nanoTime
        _          <- TestRandom.setSeed(time)
      } yield testRandom
    }
  }

  /**
   * Constructs a new `Test` object that implements the `TestRandom` interface.
   * This can be useful for mixing in with implementations of other interfaces.
   */
  def makeTest(data: Data)(implicit trace: ZTraceElement): UIO[Test] =
    for {
      data   <- ZIO.succeed(Ref.unsafeMake(data))
      buffer <- ZIO.succeed(Ref.unsafeMake(Buffer()))
    } yield Test(data, buffer)

  /**
   * Accesses a `TestRandom` instance in the environment and saves the random
   * state in an effect which, when run, will restore the `TestRandom` to the
   * saved state.
   */
  def save(implicit trace: ZTraceElement): ZIO[TestRandom, Nothing, UIO[Unit]] =
    ZIO.serviceWithZIO(_.save)

  /**
   * Accesses a `TestRandom` instance in the environment and sets the seed to
   * the specified value.
   */
  def setSeed(seed: => Long)(implicit trace: ZTraceElement): URIO[TestRandom, Unit] =
    ZIO.serviceWithZIO(_.setSeed(seed))

  /**
   * The buffer of the `TestRandom`.
   */
  final case class Buffer(
    booleans: List[Boolean] = List.empty,
    bytes: List[Chunk[Byte]] = List.empty,
    chars: List[Char] = List.empty,
    doubles: List[Double] = List.empty,
    floats: List[Float] = List.empty,
    integers: List[Int] = List.empty,
    longs: List[Long] = List.empty,
    strings: List[String] = List.empty,
    uuids: List[UUID] = List.empty
  )
}