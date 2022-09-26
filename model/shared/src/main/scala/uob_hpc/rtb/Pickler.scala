package uob_hpc.rtb

object Pickler extends upickle.AttributeTagged {

  override implicit def OptionWriter[T: Writer]: Writer[Option[T]] =
    implicitly[Writer[T]].comap[Option[T]] {
      case None    => null.asInstanceOf[T]
      case Some(x) => x
    }

  override implicit def OptionReader[T: Reader]: Reader[Option[T]] =
    new Reader.Delegate[Any, Option[T]](implicitly[Reader[T]].map(Some(_))) {
      override def visitNull(index: Int) = None
    }

  import java.time.Instant
  import java.time.LocalDate

  given ReadWriter[Instant] = readwriter[Long].bimap[Instant](_.toEpochMilli, Instant.ofEpochMilli(_))
  given ReadWriter[LocalDate] = readwriter[Array[Int]].bimap[LocalDate](
    ld => Array(ld.getYear, ld.getMonthValue, ld.getDayOfMonth),
    { case Array(yyyy, mm, dd) => LocalDate.of(yyyy, mm, dd) }
  )

}
