// Copyright (c) 2016-2018 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package seqexec.engine

import java.util.UUID
import cats.data.Kleisli
import cats.effect.IO
import seqexec.model.enum.Instrument.{ F2, GmosS }
import seqexec.model.{ SequenceMetadata, SequenceState, StepState, StepConfig }
import seqexec.model.enum.Resource
import seqexec.model.{ActionType, UserDetails}
import fs2.async.mutable.Queue
import fs2.{Stream, async}
import gem.Observation
import org.scalatest.Inside._
import org.scalatest.Matchers._
import org.scalatest._
import scala.Function.const
import scala.concurrent.ExecutionContext.Implicits.global

@SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
class StepSpec extends FlatSpec {

  private val seqId = Observation.Id.unsafeFromString("GS-2017B-Q-1-1")
  private val user = UserDetails("telops", "Telops")

  implicit object UnitCanGenerateActionMetadata extends ActionMetadataGenerator[Unit] {
    override def generate(a: Unit)(v: ActionMetadata): ActionMetadata = v
  }
  private val executionEngine = new Engine[Unit, Unit]

  private val observeResult = Result.Observed("dummyId")
  private val result = Result.OK(observeResult)
  private val failure = Result.Error("Dummy error")
  private val actionFailed =  fromIO(ActionType.Undefined, IO(failure)).copy(state = Action.State(Action.Failed(failure), Nil))
  private val action: Action = fromIO(ActionType.Undefined, IO(result))
  private val actionCompleted: Action = action.copy(state = Action.State(Action.Completed(observeResult), Nil))
  private val config: StepConfig = Map.empty
  def simpleStep(pending: List[Actions], focus: Execution, done: List[Results]): Step.Zipper = {
    val rollback: (Execution, List[Actions]) = done.map(_.map(const(action))) ++ List(focus.execution.map(const(action))) ++ pending match {
      case Nil => (Execution.empty, Nil)
      case x::xs => (Execution(x), xs)
    }

    Step.Zipper(1, None, config, Set.empty, breakpoint = Step.BreakpointMark(false), skipMark = Step.SkipMark(false), pending, focus, done.map(_.map{ r =>
      val x = fromIO(ActionType.Observe, IO(r))
      x.copy(state = Execution.actionStateFromResult(r)(x.state))
    }), rollback)
  }

  val stepz0: Step.Zipper   = simpleStep(Nil, Execution.empty, Nil)
  val stepza0: Step.Zipper  = simpleStep(List(List(action)), Execution.empty, Nil)
  val stepza1: Step.Zipper  = simpleStep(List(List(action)), Execution(List(actionCompleted)), Nil)
  val stepzr0: Step.Zipper  = simpleStep(Nil, Execution.empty, List(List(result)))
  val stepzr1: Step.Zipper  = simpleStep(Nil, Execution(List(actionCompleted, actionCompleted)), Nil)
  val stepzr2: Step.Zipper  = simpleStep(Nil, Execution(List(actionCompleted, actionCompleted)), List(List(result)))
  val stepzar0: Step.Zipper = simpleStep(Nil, Execution(List(actionCompleted, action)), Nil)
  val stepzar1: Step.Zipper = simpleStep(List(List(action)), Execution(List(actionCompleted, actionCompleted)), List(List(result)))
  private val startEvent = Event.start(seqId, user, UUID.randomUUID())

  /**
    * Emulates TCS configuration in the real world.
    *
    */
  val configureTcs: Action  = fromIO(ActionType.Configure(Resource.TCS),
    for {
      _ <- IO(println("System: Start TCS configuration"))
      _ <- IO(Thread.sleep(200))
      _ <- IO(println ("System: Complete TCS configuration"))
    } yield Result.OK(Result.Configured(Resource.TCS)))

  /**
    * Emulates Instrument configuration in the real world.
    *
    */
  val configureInst: Action  = fromIO(ActionType.Configure(GmosS),
    for {
      _ <- IO(println("System: Start Instrument configuration"))
      _ <- IO(Thread.sleep(150))
      _ <- IO(println("System: Complete Instrument configuration"))
    } yield Result.OK(Result.Configured(GmosS)))

  /**
    * Emulates an observation in the real world.
    *
    */
  val observe: Action  = fromIO(ActionType.Observe,
    for {
    _ <- IO(println("System: Start observation"))
    _ <- IO(Thread.sleep(200))
    _ <- IO(println ("System: Complete observation"))
  } yield Result.OK(Result.Observed("DummyFileId")))

  def error(errMsg: String): Action  = fromIO(ActionType.Undefined,
    IO {
      Thread.sleep(200)
      Result.Error(errMsg)
    }
  )

  def triggerPause(q: async.mutable.Queue[IO, executionEngine.EventType]): Action = fromIO(ActionType.Undefined,
    for {
      _ <- q.enqueue1(Event.pause(seqId, user))
      // There is not a distinct result for Pause because the Pause action is a
      // trick for testing but we don't need to support it in real life, the pause
      // input event is enough.
    } yield Result.OK(Result.Configured(Resource.TCS)))

  def triggerStart(q: IO[async.mutable.Queue[IO, executionEngine.EventType]]): Action = fromIO(ActionType.Undefined,
    for {
      _ <- q.map(_.enqueue1(Event.start(seqId, user, UUID.randomUUID())))
      // Same case that the pause action
    } yield Result.OK(Result.Configured(Resource.TCS)))

  def isFinished(status: SequenceState): Boolean = status match {
    case SequenceState.Idle      => true
    case SequenceState.Completed => true
    case SequenceState.Failed(_) => true
    case _                       => false
  }

  def runToCompletion(s0: Engine.State[Unit]): Option[Engine.State[Unit]] = {
    executionEngine.process(Stream.eval(IO.pure(Event.start(seqId, user, UUID.randomUUID()))))(s0).drop(1).takeThrough(
      a => !isFinished(a._2.sequences(seqId).status)
    ).compile.last.unsafeRunSync.map(_._2)
  }

  def runToCompletionL(s0: Engine.State[Unit]): List[Engine.State[Unit]] = {
    executionEngine.process(Stream.eval(IO.pure(Event.start(seqId, user, UUID.randomUUID()))))(s0).drop(1).takeThrough(
      a => !isFinished(a._2.sequences(seqId).status)
    ).compile.toVector.unsafeRunSync.map(_._2).toList
  }

  // This test must have a simple step definition and the known sequence of updates that running that step creates.
  // The test will just run step and compare the output with the predefined sequence of updates.
  ignore should "run and generate the predicted sequence of updates." in {

  }

  // The difficult part is to set the pause command to interrupts the step execution in the middle.
  "pause" should "stop execution in response to a pause command" in {
    val q: Stream[IO, Queue[IO, executionEngine.EventType]] = Stream.eval(async.boundedQueue[IO, executionEngine.EventType](10))
    def qs0(q: async.mutable.Queue[IO, executionEngine.EventType]): Engine.State[Unit] =
      Engine.State[Unit](
        userData = (),
        sequences = Map(
          (seqId,
            Sequence.State.init(
              Sequence(
                id = seqId,
                metadata = SequenceMetadata(F2, None, ""),
                steps = List(
                  Step.init(
                    id = 1,
                    fileId = None,
                    config = config,
                    resources = Set.empty,
                    executions = List(
                      List(configureTcs, configureInst, triggerPause(q)), // Execution
                      List(observe) // Execution
                    )
                  )
                )
              )
            )
          )
        )
      )
    def notFinished(v: (executionEngine.EventType, executionEngine.StateType)): Boolean =
      !isFinished(v._2.sequences(seqId).status)

    val m = for {
      k <- q
      o <- Stream.apply(qs0(k))
      _ <- Stream.eval(k.enqueue1(startEvent))
      u <- executionEngine.process(k.dequeue)(o).drop(1).takeThrough(notFinished).map(_._2)
    } yield u.sequences(seqId)

    inside (m.compile.last.unsafeRunSync()) {
      case Some(Sequence.State.Zipper(zipper, status)) =>
        inside (zipper.focus.toStep) {
          case Step(_, _, _, _, _, _, _, ex1::ex2::Nil) =>
            assert( Execution(ex1).results.length == 3 && Execution(ex2).actions.length == 1)
        }
        status should be (SequenceState.Idle)
    }

  }

  it should "resume execution from the non-running state in response to a resume command, rolling back a partially run step." in {
    // Engine state with one idle sequence partially executed. One Step completed, two to go.
    val qs0: Engine.State[Unit] =
      Engine.State[Unit](
        userData = (),
        sequences = Map(
          (seqId,
            Sequence.State.Zipper(
              Sequence.Zipper(
                id = Observation.Id.unsafeFromString("GS-2018A-Q-3-1"),
                metadata = SequenceMetadata(F2, None, ""),
                pending = Nil,
                focus = Step.Zipper(
                  id = 2,
                  fileId = None,
                  config = config,
                  resources = Set.empty,
                  breakpoint = Step.BreakpointMark(false),
                  skipMark = Step.SkipMark(false),
                  pending = Nil,
                  focus = Execution(List(observe)),
                  done = List(List(actionCompleted, actionCompleted)),
                  rolledback = (Execution(List(configureTcs, configureInst)), List(List(observe)))),
                done = Nil
              ),
              SequenceState.Idle
            )
          )
        )
      )

    val qs1: Stream[IO, Option[Sequence.State]] =
      for {
        u <- executionEngine.process(Stream.eval(IO.pure(startEvent)))(qs0).take(1)
      } yield u._2.sequences.get(seqId)

    inside (qs1.compile.last.unsafeRunSync()) {
      case Some(Some(Sequence.State.Zipper(zipper, status))) =>
        inside (zipper.focus.toStep) {
          case Step(_, _, _, _, _, _, _, ex1::ex2::Nil) =>
            assert(Execution(ex1).actions.length == 2 && Execution(ex2).actions.length == 1)
        }
        assert(status.isRunning)
    }

  }

  it should "cancel a pause request in response to a cancel pause command." in {
    val qs0: Engine.State[Unit] =
      Engine.State[Unit](
        userData = (),
        sequences = Map(
          (seqId,
            Sequence.State.Zipper(
              Sequence.Zipper(
                id = Observation.Id.unsafeFromString("GN-2017A-Q-7-1"),
                metadata = SequenceMetadata(F2, None, ""),
                pending = Nil,
                focus = Step.Zipper(
                  id = 2,
                  fileId = None,
                  config = config,
                  resources = Set.empty,
                  breakpoint = Step.BreakpointMark(false),
                  skipMark = Step.SkipMark(false),
                  pending = Nil,
                  focus = Execution(List(observe)),
                  done = List(List(actionCompleted, actionCompleted)),
                  rolledback = (Execution(List(configureTcs, configureInst)), List(List(observe)))),
                done = Nil
              ),
              SequenceState.Running(userStop = true, internalStop = false)
            )
          )
        )
      )

    val qs1 = executionEngine.process(Stream.eval(IO.pure(Event.cancelPause(seqId, user))))(qs0).take(1).compile.last.unsafeRunSync.map(_._2)

    inside (qs1.flatMap(_.sequences.get(seqId))) {
      case Some(Sequence.State.Zipper(_, status)) =>
        assert(status.isRunning)
    }

  }

  "engine" should "ignore pause command if step is not being executed." in {
    val qs0: Engine.State[Unit] =
      Engine.State[Unit](
        userData = (),
        sequences = Map(
          (seqId,
            Sequence.State.init(
              Sequence(
                id = seqId,
                metadata = SequenceMetadata(F2, None, ""),
                steps = List(
                  Step.init(
                    id = 1,
                    fileId = None,
                    config = config,
                    resources = Set.empty,
                    executions = List(
                      List(configureTcs, configureInst), // Execution
                      List(observe) // Execution
                    )
                  )
                )
              )
            )
          )
        )
      )
    val qss = executionEngine.process(Stream.eval(IO.pure(Event.pause(seqId, user))))(qs0).take(1).compile.last.unsafeRunSync.map(_._2)

    inside (qss.flatMap(_.sequences.get(seqId))) {
      case Some(Sequence.State.Zipper(zipper, status)) =>
        inside (zipper.focus.toStep) {
          case Step(_, _, _, _, _, _, _, ex1::ex2::Nil) =>
            assert( Execution(ex1).actions.length == 2 && Execution(ex2).actions.length == 1)
        }
        status should be (SequenceState.Idle)
    }
  }

  // Be careful that start command doesn't run an already running sequence.
  "engine" should "ignore start command if step is already running." in {
    val q = async.boundedQueue[IO, executionEngine.EventType](10)
    val qs0: Engine.State[Unit] =
      Engine.State[Unit](
        userData = (),
        sequences = Map(
          (seqId,
            Sequence.State.init(
              Sequence(
                id = seqId,
                metadata = SequenceMetadata(F2, None, ""),
                steps = List(
                  Step.init(
                    id = 1,
                    fileId = None,
                    config = config,
                    resources = Set.empty,
                    executions = List(
                      List(configureTcs, configureInst), // Execution
                      List(triggerStart(q)), // Execution
                      List(observe) // Execution
                    )
                  )
                )
              )
            )
          )
        )
      )

    val qss = q.flatMap { k => k.enqueue1(Event.start(seqId, user, UUID.randomUUID)).flatMap(_ => executionEngine.process(k.dequeue)(qs0).drop(1).takeThrough(
      a => !isFinished(a._2.sequences(seqId).status)
    ).compile.toVector)}.unsafeRunSync

    val actionsCompleted = qss.map(_._1).collect{case x@EventSystem(_: Completed[_]) => x}
    assert(actionsCompleted.length == 4)

    val executionsCompleted = qss.map(_._1).collect{case x@EventSystem(_: Executed) => x}
    assert(executionsCompleted.length == 3)

    val sequencesCompleted = qss.map(_._1).collect{case x@EventSystem(_: Finished) => x}
    assert(sequencesCompleted.length == 1)

    inside (qss.lastOption.flatMap(_._2.sequences.get(seqId))) {
      case Some(Sequence.State.Final(_, status)) =>
        status should be (SequenceState.Completed)
    }
  }

  // For this test, one of the actions in the step must produce an error as result.
  "engine" should "stop execution and propagate error when an Action ends in error." in {
    val errMsg = "Dummy error"
    val qs0: Engine.State[Unit] =
      Engine.State[Unit](
        userData = (),
        sequences = Map(
          (seqId,
            Sequence.State.init(
              Sequence(
                id = seqId,
                metadata = SequenceMetadata(F2, None, ""),
                steps = List(
                  Step.init(
                    id = 1,
                    fileId = None,
                    config = config,
                    resources = Set.empty,
                    executions = List(
                      List(configureTcs, configureInst), // Execution
                      List(error(errMsg)),
                      List(observe)
                    )
                  )
                )
              )
            )
          )
        )
      )

    val qs1 = runToCompletion(qs0)

    inside (qs1.flatMap(_.sequences.get(seqId))) {
      case Some(Sequence.State.Zipper(zipper, status)) =>
        inside (zipper.focus.toStep) {
          // Check that the sequence stopped midway
          case Step(_, _, _, _, _, _, _, ex1::ex2::ex3::Nil) =>
            assert( Execution(ex1).results.length == 2 && Execution(ex2).results.length == 1 && Execution(ex3).actions.length == 1)
        }
        // And that it ended in error
        status should be (SequenceState.Failed(errMsg))
    }
  }

  "engine" should "record a partial result and continue execution." in {

    // For result types
    case class RetValDouble(v: Double) extends Result.RetVal
    case class PartialValDouble(v: Double) extends Result.PartialVal

    val qs0: Engine.State[Unit] =
      Engine.State[Unit](
        userData = (),
        sequences = Map(
          (seqId,
            Sequence.State.init(
              Sequence(
                id = seqId,
                metadata = SequenceMetadata(F2, None, ""),
                steps = List(
                  Step.init(
                    id = 1,
                    fileId = None,
                    config = config,
                    resources = Set.empty,
                    executions = List(
                      List(
                        fromIO(ActionType.Undefined,
                          IO(
                            Result.Partial(
                              PartialValDouble(0.5),
                              Kleisli(_ => IO(Result.OK(RetValDouble(1.0)))
                              )
                            )
                          )
                        )
                      )
                    )
                  )
                )
              )
            )
          )
        )
      )

    val qss = runToCompletionL(qs0)

    inside (qss.drop(1).headOption.flatMap(_.sequences.get(seqId))) {
      case Some(Sequence.State.Zipper(zipper, status)) =>
        inside (zipper.focus.focus.execution.headOption) {
          case Some(Action(_, _, Action.State(Action.Started, v::_))) => v shouldEqual PartialValDouble(0.5)
        }
        assert(status.isRunning)
    }
    inside (qss.lastOption.flatMap(_.sequences.get(seqId))) {
      case Some(Sequence.State.Final(seq, status)) =>
        seq.steps.headOption.flatMap(_.executions.headOption.flatMap(_.headOption)).map(_.state.runState) shouldEqual Some(Action.Completed(RetValDouble(1.0)))
        status shouldBe SequenceState.Completed
    }

  }

  "uncurrentify" should "be None when not all executions are completed" in {
    assert(stepz0.uncurrentify.isEmpty)
    assert(stepza0.uncurrentify.isEmpty)
    assert(stepza1.uncurrentify.isEmpty)
    assert(stepzr0.uncurrentify.isEmpty)
    assert(stepzr1.uncurrentify.nonEmpty)
    assert(stepzr2.uncurrentify.nonEmpty)
    assert(stepzar0.uncurrentify.isEmpty)
    assert(stepzar1.uncurrentify.isEmpty)
  }

  "next" should "be None when there are no more pending executions" in {
    assert(stepz0.next.isEmpty)
    assert(stepza0.next.isEmpty)
    assert(stepza1.next.nonEmpty)
    assert(stepzr0.next.isEmpty)
    assert(stepzr1.next.isEmpty)
    assert(stepzr2.next.isEmpty)
    assert(stepzar0.next.isEmpty)
    assert(stepzar1.next.nonEmpty)
  }

  val step0: Step = Step.init(1, None, config, Set.empty, List(Nil))
  val step1: Step = Step.init(1, None, config, Set.empty, List(List(action)))
  val step2: Step = Step.init(2, None, config, Set.empty, List(List(action, action), List(action)))

  "currentify" should "be None only when a Step is empty of executions" in {
    assert(Step.Zipper.currentify(Step.init(0, None, config, Set.empty, Nil)).isEmpty)
    assert(Step.Zipper.currentify(step0).isEmpty)
    assert(Step.Zipper.currentify(step1).nonEmpty)
    assert(Step.Zipper.currentify(step2).nonEmpty)
  }

  "status" should "be completed when it doesn't have any executions" in {
    assert(Step.status(stepz0.toStep) === StepState.Completed)
  }

  "status" should "be Error when at least one Action failed" in {
    assert(
      Step.status(
        Step.Zipper(
          id = 1,
          fileId = None,
          config = Map.empty,
          resources = Set.empty,
          breakpoint = Step.BreakpointMark(false),
          skipMark = Step.SkipMark(false),
          pending = Nil,
          focus = Execution(List(action, actionFailed, actionCompleted)),
          done = Nil,
          rolledback = (Execution(List(action, action, action)), Nil)
        ).toStep
      ) === StepState.Failed("Dummy error")
    )
  }

  "status" should "be Completed when all actions succeeded" in {
    assert(
      Step.status(
        Step.Zipper(
          id = 1,
          fileId = None,
          config = Map.empty,
          resources = Set.empty,
          breakpoint = Step.BreakpointMark(false),
          skipMark = Step.SkipMark(false),
          pending = Nil,
          focus = Execution(List(actionCompleted, actionCompleted, actionCompleted)),
          done = Nil,
          rolledback = (Execution(List(action, action, action)), Nil)
        ).toStep
      ) === StepState.Completed
    )
  }

  "status" should "be Running when there are both actions and results" in {
    assert(
      Step.status(
        Step.Zipper(
          id = 1,
          fileId = None,
          config = Map.empty,
          resources = Set.empty,
          breakpoint = Step.BreakpointMark(false),
          skipMark = Step.SkipMark(false),
          pending = Nil,
          focus = Execution(List(actionCompleted, action, actionCompleted)),
          done = Nil,
          rolledback = (Execution(List(action, action, action)), Nil)
        ).toStep
      ) === StepState.Running
    )
  }

  "status" should "be Pending when there are only pending actions" in {
    assert(
      Step.status(
        Step.Zipper(
          id = 1,
          fileId = None,
          config = Map.empty,
          resources = Set.empty,
          breakpoint = Step.BreakpointMark(false),
          skipMark = Step.SkipMark(false),
          pending = Nil,
          focus = Execution(List(action, action, action)),
          done = Nil,
          rolledback = (Execution(List(action, action, action)), Nil)
        ).toStep
      ) === StepState.Pending
    )
  }

  "status" should "be Skipped if the Step was skipped" in {
    assert(
      Step.status(
        Step.Zipper(
          id = 1,
          fileId = None,
          config = Map.empty,
          resources = Set.empty,
          breakpoint = Step.BreakpointMark(false),
          skipMark = Step.SkipMark(false),
          pending = Nil,
          focus = Execution(List(action, action, action)),
          done = Nil,
          rolledback = (Execution(List(action, action, action)), Nil)
        ).toStep.copy(skipped = Step.Skipped(true))
      ) === StepState.Skipped
    )
  }

}
