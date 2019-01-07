// Copyright (c) 2016-2018 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package seqexec.server.flamingos2

import cats.data.{EitherT, StateT}
import cats.effect.{ IO, Timer }
import cats.implicits._
import seqexec.model.dhs.ImageFileId
import seqexec.server.{EpicsCodex, ObserveCommand, Progress, ProgressUtil, RemainingTime, SeqAction}
import edu.gemini.spModel.gemini.flamingos2.Flamingos2.{Decker, Filter, ReadoutMode, WindowCover, _}
import org.log4s.getLogger
import scala.concurrent.ExecutionContext
import squants.{Seconds, Time}
import squants.time.TimeConversions._

object Flamingos2ControllerEpics extends Flamingos2Controller {
  private val Log = getLogger

  import EpicsCodex._
  import Flamingos2Controller._

  override def getConfig: SeqAction[Flamingos2Config] = ??? // scalastyle:ignore

  implicit val encodeReadoutMode: EncodeEpicsValue[ReadoutMode, String] = EncodeEpicsValue {
    case ReadoutMode.SCIENCE     => "SCI"
    case ReadoutMode.ENGINEERING => "ENG"
  }

  implicit val encodeBiasMode: EncodeEpicsValue[BiasMode, String] = EncodeEpicsValue {
    case BiasMode.Imaging  => "Imaging"
    case BiasMode.LongSlit => "Long_Slit"
    case BiasMode.MOS      => "Mos"
  }

  def setDCConfig(dc: DCConfig): SeqAction[Unit] = for {
    _ <- Flamingos2Epics.instance.dcConfigCmd.setExposureTime(dc.t.toSeconds.toDouble)
    _ <- Flamingos2Epics.instance.dcConfigCmd.setNumReads(dc.n.getCount)
    _ <- Flamingos2Epics.instance.dcConfigCmd.setReadoutMode(encode(dc.r))
    _ <- Flamingos2Epics.instance.dcConfigCmd.setBiasMode(encode(dc.b))
  } yield ()

  implicit val encodeWindowCoverPosition: EncodeEpicsValue[WindowCover, String] = EncodeEpicsValue {
    case WindowCover.OPEN  => "Open"
    case WindowCover.CLOSE => "Closed"
  }

  implicit val encodeDeckerPosition: EncodeEpicsValue[Decker, String] = EncodeEpicsValue {
    case Decker.IMAGING   => "Open"
    case Decker.LONG_SLIT => "Long_Slit"
    case Decker.MOS       => "Mos"
  }

  implicit val encodeFPUPosition: EncodeEpicsValue[FocalPlaneUnit, (String, String)] = EncodeEpicsValue {
    case FocalPlaneUnit.Open        => ("Open", "null")
    case FocalPlaneUnit.GridSub1Pix => ("sub1-pix_grid", "null")
    case FocalPlaneUnit.Grid2Pix    => ("2-pix_grid", "null")
    case FocalPlaneUnit.Slit1Pix    => ("1pix-slit", "null")
    case FocalPlaneUnit.Slit2Pix    => ("2pix-slit", "null")
    case FocalPlaneUnit.Slit3Pix    => ("3pix-slit", "null")
    case FocalPlaneUnit.Slit4Pix    => ("4pix-slit", "null")
    case FocalPlaneUnit.Slit6Pix    => ("6pix-slit", "null")
    case FocalPlaneUnit.Slit8Pix    => ("8pix-slit", "null")
    case FocalPlaneUnit.Custom(s)   => ("null", s)
  }

  // Removed obsolete filter positions Open and DK_G0807
  implicit val encodeFilterPosition: EncodeEpicsValue[Filter, Option[String]] =
    EncodeEpicsValue.applyO {
    case Filter.Y       => "Y_G0811"
    case Filter.F1056   => "F1056"
    case Filter.F1063   => "F1063"
    case Filter.J_LOW   => "J-lo_G0801"
    case Filter.J       => "J_G0802"
    case Filter.H       => "H_G0803"
    case Filter.K_LONG  => "K-long_G0812"
    case Filter.K_SHORT => "Ks_G0804"
    case Filter.JH      => "JH_G0809"
    case Filter.HK      => "HK_G0806"
    case Filter.K_BLUE  => "K-blue_G0814"
    case Filter.K_RED   => "K-red_G0815"
  }

  implicit val encodeLyotPosition: EncodeEpicsValue[Lyot, String] = EncodeEpicsValue {
    case LyotWheel.OPEN       => "f/16_G5830"
    case LyotWheel.HIGH       => "null"
    case LyotWheel.LOW        => "null"
    case LyotWheel.GEMS_OVER  => "GEMS_over_G5836"
    case LyotWheel.GEMS_UNDER => "GEMS_under_G5835"
    case LyotWheel.GEMS       => "Gems_G5835"
    case LyotWheel.H1         => "Hart1_G5833"
    case LyotWheel.H2         => "Hart2_G5834"
  }

  implicit val encodeGrismPosition: EncodeEpicsValue[Grism, String] = EncodeEpicsValue {
    case Grism.Open    => "Open"
    case Grism.R1200HK => "HK_G5802"
    case Grism.R1200JH => "JH_G5801"
    case Grism.R3000   => "R3K_G5803"
    case Grism.Dark    => "DK_G5804"
  }

  def setCCConfig(cc: CCConfig): SeqAction[Unit] = {
    val fpu = encode(cc.fpu)
    val filter = encode(cc.f)
    for {
      _ <- Flamingos2Epics.instance.configCmd.setWindowCover(encode(cc.w))
      _ <- Flamingos2Epics.instance.configCmd.setDecker(encode(cc.d))
      _ <- Flamingos2Epics.instance.configCmd.setMOS(fpu._1)
      _ <- Flamingos2Epics.instance.configCmd.setMask(fpu._2)
      _ <- filter.map(Flamingos2Epics.instance.configCmd.setFilter).getOrElse(SeqAction.void)
      _ <- Flamingos2Epics.instance.configCmd.setLyot(encode(cc.l))
      _ <- Flamingos2Epics.instance.configCmd.setGrism(encode(cc.g))
    } yield ()
  }

  override def applyConfig(config: Flamingos2Config): SeqAction[Unit] = for {
    _ <- EitherT.right(IO.apply(Log.debug("Start Flamingos2 configuration")))
    _ <- setDCConfig(config.dc)
    _ <- setCCConfig(config.cc)
    _ <- Flamingos2Epics.instance.configCmd.setTimeout(ConfigTimeout)
    _ <- Flamingos2Epics.instance.post
    _ <- EitherT.right(IO(Log.debug("Completed Flamingos2 configuration")))
  } yield ()

  override def observe(fileId: ImageFileId, expTime: Time): SeqAction[ObserveCommand.Result] = for {
    _ <- Flamingos2Epics.instance.observeCmd.setLabel(fileId)
    _ <- Flamingos2Epics.instance.observeCmd.setTimeout(expTime + ReadoutTimeout)
    _ <- Flamingos2Epics.instance.observeCmd.post
  } yield ObserveCommand.Success

  override def endObserve: SeqAction[Unit] =  for {
    _ <- EitherT.right(IO(Log.debug("Send endObserve to Flamingos2")))
    _ <- Flamingos2Epics.instance.endObserveCmd.setTimeout(DefaultTimeout)
    _ <- Flamingos2Epics.instance.endObserveCmd.mark
    _ <- Flamingos2Epics.instance.endObserveCmd.post
  } yield ()

  override def observeProgress(total: Time): fs2.Stream[IO, Progress] = {
    implicit val ioTimer: Timer[IO] = IO.timer(ExecutionContext.global)
    // ProgressUtil.countdown[IO](total, Seconds(0))
    val s = ProgressUtil.fromStateTOption[IO, Time](_ => StateT[IO, Time, Option[Progress]] { st =>
      IO {
        val m = if (total >= st) total else st
        val p = for {
          obst <- Flamingos2Epics.instance.observeState
          dummy = obst // Hack to avoid scala/bug#11175
          if obst.isBusy
          rem <- Flamingos2Epics.instance.countdown.map(_.seconds)
        } yield Progress(m, RemainingTime(rem))
        (m, p)
      }
    })
    s(total).dropWhile(_.remaining.self.value === 0.0) // drop leading zeros
      .takeThrough(_.remaining.self.value > 0.0) // drop all tailing zeros but the first one

  }

  val ReadoutTimeout: Time = Seconds(300)
  val DefaultTimeout: Time = Seconds(60)
  val ConfigTimeout: Time = Seconds(400)

}
