// Copyright (c) 2016-2017 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.seqexec.server.gnirs

import edu.gemini.seqexec.model.Model
import edu.gemini.seqexec.model.Model.Instrument
import edu.gemini.seqexec.model.dhs.ImageFileId
import edu.gemini.seqexec.server.ConfigUtilOps._
import edu.gemini.seqexec.server.gnirs.GnirsController.{CCConfig, DCConfig, Other, ReadMode}
import edu.gemini.seqexec.server.{ConfigResult, ConfigUtilOps, InstrumentSystem, ObserveCommand, SeqAction, SeqObserve, SeqexecFailure, TrySeq}
import edu.gemini.spModel.config2.Config
import edu.gemini.spModel.seqcomp.SeqConfigNames.INSTRUMENT_KEY
import edu.gemini.spModel.obscomp.InstConstants._
import edu.gemini.spModel.gemini.gnirs.GNIRSConstants._
import edu.gemini.spModel.gemini.gnirs.GNIRSParams._
import edu.gemini.spModel.gemini.gnirs.InstGNIRS.{ACQUISITION_MIRROR_PROP, CROSS_DISPERSED_PROP, WELL_DEPTH_PROP}
import squants.{Time, Length}
import squants.space.LengthConversions._
import squants.time.TimeConversions._

import scalaz._
import Scalaz._

class Gnirs(controller: GnirsController) extends InstrumentSystem {
  override val sfName: String = "gnirs"
  override val contributorName: String = "ngnirsdc1"
  override val dhsInstrumentName: String = "GNIRS"

  import Gnirs._

  import InstrumentSystem._
  override val observeControl: ObserveControl = InfraredControl(StopObserveCmd(controller.stopObserve),
                                                                AbortObserveCmd(controller.abortObserve))

  override def observe(config: Config): SeqObserve[ImageFileId, ObserveCommand.Result] = Reader {
    fileId => controller.observe(fileId, calcObserveTime(config))
  }

  override def calcObserveTime(config: Config): Time =
    (extractExposureTime(config) |@| extractCoadds(config))(_ * _.toDouble).getOrElse(10000.seconds)

  override val resource: Model.Resource = Instrument.GNIRS

  override def configure(config: Config): SeqAction[ConfigResult] =
    SeqAction.either(fromSequenceConfig(config)).flatMap(controller.applyConfig).map(_ => ConfigResult(this))

  override def notifyObserveEnd: SeqAction[Unit] = controller.endObserve
}

object Gnirs {
  def extractExposureTime(config: Config): ExtractFailure\/Time =
    config.extract(EXPOSURE_TIME_KEY).as[java.lang.Double].map(_.toDouble.seconds)

  def extractCoadds(config: Config): ExtractFailure\/Int =
    config.extract(INSTRUMENT_KEY / COADDS_PROP).as[java.lang.Integer].map(_.toInt)

  def fromSequenceConfig(config: Config): TrySeq[GnirsController.GnirsConfig] =
    (getCCConfig(config) |@| getDCConfig(config))(GnirsController.GnirsConfig(_, _))

  private def getDCConfig(config: Config): TrySeq[DCConfig] = (for {
    expTime <- extractExposureTime(config)
    coadds  <- extractCoadds(config)
    readMode <- config.extract(INSTRUMENT_KEY / READ_MODE_PROP).as[ReadMode]
    wellDepth <- config.extract(INSTRUMENT_KEY / WELL_DEPTH_PROP).as[WellDepth]
  } yield DCConfig(expTime, coadds, readMode, wellDepth))
    .leftMap(e => SeqexecFailure.Unexpected(ConfigUtilOps.explain(e)))

  private def getCCConfig(config: Config): TrySeq[CCConfig] = config.extract(INSTRUMENT_KEY / OBSERVE_TYPE_PROP).as[String]
    .leftMap(e => SeqexecFailure.Unexpected(ConfigUtilOps.explain(e))).flatMap{
    case DARK_OBSERVE_TYPE => GnirsController.Dark.right
    case BIAS_OBSERVE_TYPE => SeqexecFailure.Unexpected("Bias not supported for GNIRS").left
    case _                 => getCCOtherConfig(config)
  }

  private def getCCOtherConfig(config: Config): TrySeq[CCConfig] = (for {
    xdisp  <- config.extract(INSTRUMENT_KEY / CROSS_DISPERSED_PROP).as[CrossDispersed]
    woll   <- config.extract(INSTRUMENT_KEY / WOLLASTON_PRISM_PROP).as[WollastonPrism]
    mode   <- getCCMode(config, xdisp, woll)
    slit   <- config.extract(INSTRUMENT_KEY / SLIT_WIDTH_PROP).as[SlitWidth]
    slitOp = getSlit(slit)
    camera <- config.extract(INSTRUMENT_KEY / CAMERA_PROP).as[Camera]
    decker <- getDecker(config, slit, woll, xdisp)
    filter <- config.extract(INSTRUMENT_KEY / FILTER_PROP).as[Filter].toOption.right
    filter1 = getFilter1(filter, slit, decker)
    filter2 <- getFilter2(config, filter, xdisp)
  } yield Other(mode, camera, decker, filter1, filter2, slitOp) )
    .leftMap(e => SeqexecFailure.Unexpected(ConfigUtilOps.explain(e)))

  private def getCCMode(config: Config, xdispersed: CrossDispersed, woll: WollastonPrism): ConfigUtilOps.ExtractFailure\/GnirsController.Mode = for {
    acq        <- config.extract(INSTRUMENT_KEY / ACQUISITION_MIRROR_PROP).as[AcquisitionMirror]
    disperser  <- config.extract(INSTRUMENT_KEY / DISPERSER_PROP).as[Disperser]
  } yield {
    if(acq === AcquisitionMirror.IN) GnirsController.Acquisition
    else xdispersed match {
      case CrossDispersed.SXD => GnirsController.CrossDisperserS(disperser)
      case CrossDispersed.LXD => GnirsController.CrossDisperserL(disperser)
      case _                  => if(woll === WollastonPrism.YES) GnirsController.Wollaston(disperser)
                                 else GnirsController.Mirror(disperser)
    }
  }

  private def getDecker(config: Config, slit: SlitWidth, woll: WollastonPrism, xdisp: CrossDispersed): ConfigUtilOps.ExtractFailure\/GnirsController.Decker =
    config.extract(INSTRUMENT_KEY / DECKER_PROP).as[Decker].orElse{
      for {
        pixScale <- config.extract((INSTRUMENT_KEY / PIXEL_SCALE_PROP)).as[PixelScale]
      } yield xdisp match {
        case CrossDispersed.LXD => Decker.LONG_CAM_X_DISP
        case CrossDispersed.SXD => Decker.SHORT_CAM_X_DISP
        case _                  => {
          if (woll === WollastonPrism.YES) Decker.WOLLASTON
          else pixScale match {
            case PixelScale.PS_005 => Decker.LONG_CAM_LONG_SLIT
            case PixelScale.PS_015 => {
              if (slit === SlitWidth.IFU) Decker.IFU
              else Decker.SHORT_CAM_LONG_SLIT
            }
          }
        }
      }
    }

  private def getFilter1(filter: Option[Filter], slit: SlitWidth, decker: Decker): GnirsController.Filter1 =
    if(slit === SlitWidth.PUPIL_VIEWER || decker === Decker.PUPIL_VIEWER) GnirsController.Filter1.PupilViewer
    else filter.map{
      case Filter.H2_plus_ND100X | Filter.H_plus_ND100X => GnirsController.Filter1.ND100X
      case Filter.Y                                     => GnirsController.Filter1.Y_MK
      case Filter.J                                     => GnirsController.Filter1.J_MK
      case Filter.K                                     => GnirsController.Filter1.K_MK
      case _                                            => GnirsController.Filter1.Open
    }.getOrElse(GnirsController.Filter1.Open)


  private def autoFilter(w: Length): GnirsController.Filter2 = {
    val table = Map(
      GnirsController.Filter2.X -> 1.17,
      GnirsController.Filter2.J -> 1.42,
      GnirsController.Filter2.H -> 1.86,
      GnirsController.Filter2.J -> 2.70,
      GnirsController.Filter2.L -> 4.30,
      GnirsController.Filter2.M -> 6.0
    ).mapValues(_.nanometers)

    table.foldRight[GnirsController.Filter2](GnirsController.Filter2.XD){ case (t, v) => if(w < t._2) t._1 else v}
  }

  private def getFilter2(config: Config, filter: Option[Filter], xdisp: CrossDispersed): ConfigUtilOps.ExtractFailure\/GnirsController.Filter2 =
    filter.map{
      case Filter.ORDER_1        => GnirsController.Filter2.M
      case Filter.ORDER_2        => GnirsController.Filter2.L
      case Filter.ORDER_3        => GnirsController.Filter2.K
      case Filter.ORDER_4        => GnirsController.Filter2.H
      case Filter.ORDER_5        => GnirsController.Filter2.J
      case Filter.ORDER_6        => GnirsController.Filter2.X
      case Filter.X_DISPERSED    => GnirsController.Filter2.XD
      case Filter.H2             => GnirsController.Filter2.H2
      case Filter.H_plus_ND100X  => GnirsController.Filter2.H
      case Filter.H2_plus_ND100X => GnirsController.Filter2.H2
      case Filter.PAH            => GnirsController.Filter2.PAH
      case _                     => GnirsController.Filter2.Open
    }.map(_.right).getOrElse {
      if (xdisp === CrossDispersed.NO)
        config.extract(INSTRUMENT_KEY / CENTRAL_WAVELENGTH_PROP).as[java.lang.Double].map(_.toDouble.nanometers).map(autoFilter)
      else GnirsController.Filter2.XD.right
    }

  private def getSlit(slit: SlitWidth): Option[GnirsController.SlitWidth] = slit match {
    case SlitWidth.ACQUISITION => GnirsController.SlitWidth.Acquisition.some
    case SlitWidth.PINHOLE_1   => GnirsController.SlitWidth.SmallPinhole.some
    case SlitWidth.PINHOLE_3   => GnirsController.SlitWidth.LargePinhole.some
    case SlitWidth.SW_1        => GnirsController.SlitWidth.Slit0_10.some
    case SlitWidth.SW_2        => GnirsController.SlitWidth.Slit0_15.some
    case SlitWidth.SW_3        => GnirsController.SlitWidth.Slit0_20.some
    case SlitWidth.SW_4        => GnirsController.SlitWidth.Slit0_30.some
    case SlitWidth.SW_5        => GnirsController.SlitWidth.Slit0_45.some
    case SlitWidth.SW_6        => GnirsController.SlitWidth.Slit0_68.some
    case SlitWidth.SW_7        => GnirsController.SlitWidth.Slit1_00.some
    case _                     => None
  }



}