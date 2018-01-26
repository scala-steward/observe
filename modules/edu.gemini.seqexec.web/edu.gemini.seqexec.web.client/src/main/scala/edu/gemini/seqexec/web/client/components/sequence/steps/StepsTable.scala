// Copyright (c) 2016-2017 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.seqexec.web.client.components.sequence.steps

import scala.scalajs.js
import diode.react.ModelProxy
import edu.gemini.seqexec.model.Model.{Instrument, StandardStep, Step, StepState}
import japgolly.scalajs.react.vdom.TagOf
import org.scalajs.dom.html.Div
// import edu.gemini.seqexec.web.client.ModelOps._
// import edu.gemini.seqexec.web.client.model.Pages.{SeqexecPages, SequenceConfigPage}
import edu.gemini.seqexec.web.client.model.Pages.SeqexecPages
// import edu.gemini.seqexec.web.client.actions.{FlipBreakpointStep, FlipSkipStep, NavigateSilentTo}
// import edu.gemini.seqexec.web.client.circuit.{ClientStatus, SeqexecCircuit, StepsTableFocus}
import edu.gemini.seqexec.web.client.circuit.{ClientStatus, StepsTableFocus}
import edu.gemini.seqexec.web.client.components.SeqexecStyles
import edu.gemini.seqexec.web.client.components.sequence.steps.OffsetFns._
// import edu.gemini.seqexec.web.client.lenses.stepTypeO
// import edu.gemini.seqexec.web.client.semanticui.elements.icon.Icon
import edu.gemini.seqexec.web.client.semanticui.elements.icon.Icon._
// import edu.gemini.seqexec.web.client.semanticui.elements.label.Label
// import edu.gemini.seqexec.web.client.semanticui.elements.message.IconMessage
// import edu.gemini.seqexec.web.client.services.HtmlConstants.iconEmpty
import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.component.Scala.Unmounted
import japgolly.scalajs.react.vdom.html_<^._
// import org.scalajs.dom.html.Div
//
import scalacss.ScalaCssReact._
import scalacss._
import scalaz.std.AllInstances._
import scalaz.syntax.foldable._
import scalaz.syntax.equal._
// import scalaz.syntax.show._
// import scalaz.syntax.std.boolean._
import scalaz.syntax.std.option._
import react.virtualized._

object ColWidths {
  val ControlWidth: Int = 50
  val StateWidth: Int = 200
  val IdxWidth: Int = 50
  val StatusWidth: Int = 100
  val OffsetWidthBase: Int = 68
  val GuidingWidth: Int = 83
  val ExposureWidth: Int = 80
  val FilterWidth: Int = 150
  val FPUWidth: Int = 150
  val ObjectTypeWidth: Int = 100
}

/**
  * Container for a table with the steps
  */
object StepsTable {
  private val CssSettings = scalacss.devOrProdDefaults
  import CssSettings._

  val HeightWithOffsets: Int = 40
  val BreakpointLineHeight: Int = 5

  // ScalaJS defined trait
  // scalastyle:off
  trait StepRow extends js.Object {
    var step: Step
  }
  // scalastyle:on
  object StepRow {
    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def apply(step: Step): StepRow = {
      val p = (new js.Object).asInstanceOf[StepRow]
      p.step = step
      p
    }

    def unapply(l: StepRow): Option[(Step)] =
      Some((l.step))

    val Zero: StepRow = apply(Step.Zero)
  }

  final case class Props(router: RouterCtl[SeqexecPages], stepsTable: ModelProxy[(ClientStatus, Option[StepsTableFocus])], onStepToRun: Int => Callback) {
    def status: ClientStatus = stepsTable()._1
    def steps: Option[StepsTableFocus] = stepsTable()._2
    private val stepsList: List[Step] = ~steps.map(_.steps)
    def rowCount: Int = stepsList.length
    def rowGetter(idx: Int): StepRow = steps.flatMap(_.steps.index(idx)).fold(StepRow.Zero)(StepRow.apply)
    // Find out if offsets should be displayed
    val offsetsDisplay: OffsetsDisplay = stepsList.offsetsDisplay
  }

  val controlHeaderRenderer: HeaderRenderer[js.Object] = (_, _, _, _, _, _) =>
      <.span(
        SeqexecStyles.centeredCell,
        SeqexecStyles.controlCellHeader,
        ^.title := "Control",
        IconSettings
      )

  def stepControlRenderer(f: StepsTableFocus, p: Props, recalculateHeightsCB: Int => Callback): CellRenderer[js.Object, js.Object, StepRow] = (_, _, _, row: StepRow, _) =>
    StepToolsCell(StepToolsCell.Props(p.status, f, row.step, rowHeight(p)(row.step.id), recalculateHeightsCB))

  val stepIdRenderer: CellRenderer[js.Object, js.Object, StepRow] = (_, _, _, row: StepRow, _) =>
    StepIdCell(row.step.id)

  def stepProgressRenderer(f: StepsTableFocus, p: Props): CellRenderer[js.Object, js.Object, StepRow] = (_, _, _, row: StepRow, _) =>
    StepProgressCell(StepProgressCell.Props(p.status, f, row.step))

  def stepStatusRenderer(offsetsDisplay: OffsetsDisplay): CellRenderer[js.Object, js.Object, StepRow] = (_, _, _, row: StepRow, _) =>
    OffsetsDisplayCell(OffsetsDisplayCell.Props(offsetsDisplay, row.step))

  val stepGuidingRenderer: CellRenderer[js.Object, js.Object, StepRow] = (_, _, _, row: StepRow, _) =>
    GuidingCell(GuidingCell.Props(row.step))

  def stepExposureRenderer(i: Instrument): CellRenderer[js.Object, js.Object, StepRow] = (_, _, _, row: StepRow, _) =>
    ExposureTimeCell(ExposureTimeCell.Props(row.step, i))

  def stepFilterRenderer(i: Instrument): CellRenderer[js.Object, js.Object, StepRow] = (_, _, _, row: StepRow, _) =>
    FilterCell(FilterCell.Props(row.step, i))

  def stepFPURenderer(i: Instrument): CellRenderer[js.Object, js.Object, StepRow] = (_, _, _, row: StepRow, _) =>
    FPUCell(FPUCell.Props(row.step, i))

  val stepObjectTypeRenderer: CellRenderer[js.Object, js.Object, StepRow] = (_, _, _, row: StepRow, _) =>
    ObjectTypeCell(row.step)

  private def stepRowStyle(step: Step): StyleA = step match {
    case s if s.hasError                                   => SeqexecStyles.rowError
    case s if s.status === StepState.Running               => SeqexecStyles.rowWarning
    case s if s.status === StepState.Paused                => SeqexecStyles.rowNegative
    case s if s.status === StepState.Skipped               => SeqexecStyles.rowActive
    case s if (s.skip || s.status === StepState.Completed) => SeqexecStyles.rowDisabled
    case _                                                 => SeqexecStyles.rowNone
  }

  def rowClassName(p: Props)(i: Int): String = ((i, p.rowGetter(i)) match {
    case (-1, _)                                                   =>
      SeqexecStyles.headerRowStyle
    case (_, StepRow(s @ StandardStep(_, _, _, true, _, _, _, _))) =>
      (SeqexecStyles.stepRowWithBreakpoint + stepRowStyle(s))
    case (_, StepRow(s))                                           =>
      (SeqexecStyles.stepRow + stepRowStyle(s))
    case _                                                         =>
      SeqexecStyles.stepRow
  }).htmlClass

  def rowHeight(p: Props)(i: Int): Int = (p.rowGetter(i), p.offsetsDisplay) match {
    case (StepRow(StandardStep(_, _, _, true, _, _, _, _)), OffsetsDisplay.DisplayOffsets(_)) =>
      HeightWithOffsets + BreakpointLineHeight
    case (_, OffsetsDisplay.DisplayOffsets(_))                                                =>
      HeightWithOffsets
    case (StepRow(StandardStep(_, _, _, true, _, _, _, _)), _)                                =>
      SeqexecStyles.rowHeight + BreakpointLineHeight
    case _ =>
      SeqexecStyles.rowHeight
  }

  // Columns for the table
  private def columns(p: Props, recalculateHeightsCB: Int => Callback): List[Table.ColumnArg] = {
    val offsetColumn =
      p.offsetsDisplay match {
        case OffsetsDisplay.DisplayOffsets(x) =>
          Column(Column.props(ColWidths.OffsetWidthBase + x, "offset", label = "Offset", disableSort = true, cellRenderer = stepStatusRenderer(p.offsetsDisplay))).some
        case _ => None
      }
      List(
        p.steps.map(i => Column(Column.props(ColWidths.ControlWidth, "ctl", label = "Icon", disableSort = true, cellRenderer = stepControlRenderer(i, p, recalculateHeightsCB), className = SeqexecStyles.controlCellRow.htmlClass, headerRenderer = controlHeaderRenderer))),
        Column(Column.props(ColWidths.IdxWidth, "idx", label = "Step", disableSort = true, cellRenderer = stepIdRenderer)).some,
        p.steps.map(i => Column(Column.props(ColWidths.StateWidth, "state", label = "Control", flexGrow = 1, disableSort = true, cellRenderer = stepProgressRenderer(i, p)))),
        offsetColumn,
        Column(Column.props(ColWidths.GuidingWidth, "guiding", label = "Guiding", disableSort = true, cellRenderer = stepGuidingRenderer)).some,
        p.steps.map(i => Column(Column.props(ColWidths.ExposureWidth, "exposure", label = "Exposure", disableSort = true, cellRenderer = stepExposureRenderer(i.instrument)))),
        p.steps.map(i => Column(Column.props(ColWidths.FilterWidth, "filter", label = "Filter", disableSort = true, cellRenderer = stepFilterRenderer(i.instrument)))),
        p.steps.map(i => Column(Column.props(ColWidths.FPUWidth, "fpu", label = "FPU", disableSort = true, cellRenderer = stepFPURenderer(i.instrument)))),
        p.steps.map(i => Column(Column.props(ColWidths.ObjectTypeWidth, "type", label = "Type", disableSort = true, cellRenderer = stepObjectTypeRenderer)))
      ).collect { case Some(x) => x }
  }

  class Backend {
    def stepsTableProps(p: Props)(size: Size): Table.Props = {
      Table.props(
        disableHeader = false,
        noRowsRenderer = () =>
          <.div(
            ^.cls := "ui center aligned segment noRows",
            ^.height := 270.px,
            "No log entries"
          ),
        overscanRowCount = SeqexecStyles.overscanRowCount,
        height = size.height.toInt,
        rowCount = p.rowCount,
        rowHeight = rowHeight(p) _,
        rowClassName = rowClassName(p) _,
        width = size.width.toInt,
        rowGetter = p.rowGetter _,
        headerClassName = SeqexecStyles.tableHeader.htmlClass,
        headerHeight = SeqexecStyles.headerHeight)
    }

    // Create a ref
    private val ref = JsComponent.mutableRefTo(Table.component)

    private def recalculateHeightsCB(row: Int): Callback = Callback {
      ref.value.raw.recomputeRowHeights(row)
    }

    def render(p: Props): TagOf[Div] =
      <.div(
        SeqexecStyles.stepsListPane.unless(p.status.isLogged),
        SeqexecStyles.stepsListPaneWithControls.when(p.status.isLogged),
        p.steps.whenDefined { tab =>
          tab.stepConfigDisplayed.map { i =>
            <.div("CONFIG")
          }.getOrElse {
            AutoSizer(AutoSizer.props(s => ref.component(stepsTableProps(p)(s))(columns(p, recalculateHeightsCB).map(_.vdomElement): _*)))
          }
        }
      )

  }

  private val component = ScalaComponent.builder[Props]("Steps")
    .renderBackend[Backend]
    .build

  def apply(p: Props): Unmounted[Props, Unit, Backend] = component(p)
}
