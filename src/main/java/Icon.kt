import org.apache.commons.math3.complex.Complex
import java.awt.image.BufferedImage
import java.awt.image.WritableRaster
import java.io.File
import java.util.*
import javax.imageio.ImageIO

operator fun Complex.times(other: Complex): Complex = this.multiply(other)
operator fun Complex.minus(other: Complex): Complex = this.subtract(other)
operator fun Complex.plus(other: Complex): Complex = this.add(other)

data class ColorChannel(val range: ClosedRange<Long>, val red: Double, val green: Double, val blue: Double)

data class RenderArea(
    val side: Int,
    val viewXRange: ClosedFloatingPointRange<Double>,
    val viewYRange: ClosedFloatingPointRange<Double>
)
{
  fun withinBounds(x: Int, y: Int): Boolean
  {
    return x >= 0 && y >= 0 && x < side && y < side
  }

  fun getResolution(): Int
  {
    return side * side
  }

  fun getColorResolution(): Int
  {
    return getResolution() * 3 // red green blue
  }
}

data class MandelPoint(val iterations: Long?, val startValue: Complex, val endValue: Complex?)


fun mandelbrot(point: Complex, maxIter: Long, loopConsumer: (Complex) -> Unit = {}): MandelPoint
{
  var iter = 0L
  var next = point
  while ((iter < maxIter) and (next.abs() < 16.0))
  {
    val tmp = (next * next) + point
    loopConsumer(tmp)
    next = tmp
    iter += 1
  }

  fun inside() = iter >= maxIter

  return MandelPoint(if (inside()) Long.MAX_VALUE else iter, point, if (inside()) null else next)
}

fun <In, Out> linearInterpolation(value: In, from: ClosedRange<In>, to: ClosedRange<Out>): Double
    where In : Number, In : Comparable<In>, Out : Number, Out : Comparable<Out>
{
  val origin = value.toDouble() - from.start.toDouble()
  val toRange = to.endInclusive.toDouble() - to.start.toDouble()
  val fromRange = from.endInclusive.toDouble() - from.start.toDouble()
  val ratio = toRange / fromRange
  return origin * ratio + to.start.toDouble()
}

fun save(image: BufferedImage, to: String = "saved", type: String)
{
  val outputfile = File("$to.$type")
  ImageIO.write(image, type, outputfile)
}

fun spreadToColorChannel(point: Double, red: Double, green: Double, blue: Double): DoubleArray
{
  val pointRange = 0.0..1.0
  val r = linearInterpolation(point, pointRange, 0.0..red)
  val g = linearInterpolation(point, pointRange, 0.0..green)
  val b = linearInterpolation(point, pointRange, 0.0..blue)
  return doubleArrayOf(r, g, b)
}

fun getImageFromArray(pixels: IntArray, width: Int, height: Int): BufferedImage
{
  val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
  val raster = image.data as WritableRaster
  raster.setPixels(0, 0, width, height, pixels)
  image.data = raster
  return image
}

fun xyToComplex(
    x: Int,
    y: Int,
    maxIndex: Int,
    viewXRange: ClosedFloatingPointRange<Double>,
    viewYRange: ClosedFloatingPointRange<Double>
): Complex
{
  return Complex(
      linearInterpolation(x, 0 until maxIndex, viewXRange),
      linearInterpolation(y, 0 until maxIndex, viewYRange)
  )
}

fun complexToXY(c: Complex, area: RenderArea): Pair<Int, Int>
{
  return Pair(
      linearInterpolation(c.real, area.viewXRange, 0..area.side).toInt(),
      linearInterpolation(c.imaginary, area.viewYRange, 0..area.side).toInt()
  )
}


private fun flatRendering(flatSquare: List<Pair<Int, Complex>>): IntArray
{
  val iterations = flatSquare.map { it.first }
  val min = iterations.min()!!
  val max = iterations.max()!!

  fun resetMaxToMin(it: Pair<Int, Complex>): Pair<Int, Complex> = if (it.first == max) Pair(min, it.second) else it

  return flatSquare
      .map { resetMaxToMin(it) }
      .map { it.first }
      .map { linearInterpolation(it, min..max, 0f..1f) }
      .map { spreadToColorChannel(it, 1.0, 1.0, 1.0) }
      .flatMap { it.asIterable() }
      .toDoubleArray()
      .map { linearInterpolation(it, 0.0..1.0, 0..255).toInt() }
      .toIntArray()
}


fun nebulaRendering(area: RenderArea, flatSquare: List<MandelPoint>, channels: List<ColorChannel>): DoubleArray
{
  val colorChannels = ArrayList<DoubleArray>()
  for ((range, red, green, blue) in channels)
  {
    val rendered = Array(area.side, { LongArray(area.side) })
    val candidates = flatSquare.filter { it.iterations != null && it.iterations in range }

    val render: (Complex) -> Unit =
        {
          val xy = complexToXY(it, area)
          val x = xy.first
          val y = xy.second
          if (area.withinBounds(x, y))
          {
            // flipping X and Y rotates by +90 degrees
            rendered[x][y]++
          }
        }

    for (point in candidates)
    {
      mandelbrot(point.startValue, point.iterations!!, render)
    }

    val flat = rendered.flatMap { it.asIterable() }.toLongArray()
    val min = flat.min()!!
    val max = flat.max()!!
    val colorChannel = flat
        .map { linearInterpolation(it, min..max, 0.0..1.0) }
        .map { spreadToColorChannel(it, red, green, blue) }
        .flatMap { it.asIterable() }
        .toDoubleArray()

    colorChannels.add(colorChannel)
  }

  val combined = DoubleArray(area.getColorResolution())
  for (color in colorChannels)
  {
    for (index in 0 until color.size)
    {
      combined[index] += color[index]
    }
  }

  return combined
}

fun main(args: Array<String>)
{
  println("Init")
  val chunkSize = 1024 * 1024 * 4
  val computeXRange = -2.0..2.0
  val computeYRange = -2.0..2.0

  val side = 128
  val viewXRange = -2.5..1.5
  val viewYRange = -2.0..2.0

  val maxIter = 256 * 16L

  val random = Random()

  val cumulative = DoubleArray(side * side * 3)

  var pass = 0
  while (true)
  {
    pass++
    println("Mandelbrot pass $pass")
    val flatSquare = Array(
        chunkSize,
        {
          Complex(
              linearInterpolation(random.nextDouble(), 0.0..1.0, computeXRange),
              linearInterpolation(random.nextDouble(), 0.0..1.0, computeYRange)
          )
        }
    )
        .map { mandelbrot(it, maxIter) }
        .filter { it.endValue != null }
    println("Found ${flatSquare.size} interresting points [${flatSquare.filter { it.iterations in 0L..16L }.size}, ${flatSquare.filter { it.iterations in 17L..256L }.size}, ${flatSquare.filter { it.iterations in 257L until maxIter }.size}]")

    println("Nebulabrot pass $pass")
    val nebula = nebulaRendering(
        RenderArea(side, viewXRange, viewYRange),
        flatSquare,
        listOf(
            ColorChannel(0L..16L, 0.0, 0.0, 1.0),
            ColorChannel(17L..256L, 0.0, 1.0, 0.0),
            ColorChannel(257L until maxIter, 1.0, 0.0, 0.0)
        )
    )

    println("Normalize and write")
    nebula.mapIndexed { index, value -> cumulative[index] += value }

    val max = cumulative.max()!!
    val normalized = cumulative.map { linearInterpolation(it, 0.0..max, 0..255).toInt() }.toIntArray()
    val image = getImageFromArray(normalized, side, side)
    save(image, type = "png")
    save(image, to = "./pass/$pass", type = "jpg")
  }
}

