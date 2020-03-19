package util

object Metrics {

  type Min = Long
  type Max = Long
  type Mea = Long
  type Med = Long
  type StD = Long

  case class M(min: Min, max: Max, mea: Mea, med: Med, std: StD) {
    override def toString: String = {
      s"* Values in [$min,$max]\n" ++
      s"* Mean: $mea , Median: $med\n" ++
      s"* Standard deviation: $std"
    }
  }

  def mean(l: List[Long]): Long = l.sum / l.length

  def median(l: List[Long]): Long = {
    val s = l.sorted
    val split: Int = s.length / 2
    if (s.length % 2 == 0) (s(split - 1) + s(split))/2 else s(split)
  }

  def stddev(l: List[Long]): Long = {
    val  mea = mean(l)
    val sums = l.map(v => (v - mea) * (v - mea))
    sums.sum/(l.length-1)
  }

  def all(l: List[Long]): M = M(l.min, l.max, mean(l), median(l), stddev(l))

}
