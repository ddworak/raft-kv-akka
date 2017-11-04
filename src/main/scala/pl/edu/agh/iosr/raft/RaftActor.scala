package pl.edu.agh.iosr.raft

import akka.actor.{Actor, ActorRef, Cancellable}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

class RaftActor(id: Id, config: RaftConfig) extends Actor {
  /*  import DistributedPubSubMediator.{ Subscribe, SubscribeAck }
    val mediator = DistributedPubSub(context.system).mediator
    // subscribe to the topic named "content"
    mediator ! Subscribe("content", self)*/

  //persistent state on all
  /**
    * latest term server has seen
    */
  var currentTerm = Term(0)

  /**
    * candidateId that received vote in current term
    */
  var votedFor: Option[Id] = None

  /**
    * log entries; each entry contains command for state machine,
    * and term when entry was received by leader (first index is 1)
    */
  val log: ArrayBuffer[Entry] = mutable.ArrayBuffer.empty[Entry]

  //volatile state on all
  /**
    * index of the highest log entry known to be committed (initialized to 0, increases monotonically)
    */
  var commitIndex: Int = 0

  /**
    * index of the highest log entry applied to the state machine (initialized to 0, increases monotonically)
    */
  var lastApplied: Int = 0

  //volatile state on leaders
  /**
    * for each server, index of the next log entry to send to that server (initialized to leader last log index + 1)
    */
  val nextIndex: ArrayBuffer[Int] = Stream.fill(nodes().size)(1).to[ArrayBuffer]

  /**
    * for each server, index of highest log entry known to be replicated on server (initialized to 0, increases monotonically)
    */
  val matchIndex: ArrayBuffer[Int] = Stream.fill(nodes().size)(0).to[ArrayBuffer]

  var votes = 0

  import RaftActor._
  import context._

  override def receive: Receive = follower()

  private def nodes(): Vector[ActorRef] = ???

  private def broadcast(msg: Any): Unit = ???

  private def handleAppendEntries(nomination: Cancellable): Receive = {
    //1. Reply false if term < currentTerm (§5.1)
    //2. Reply false if log doesn’t contain an entry at prevLogIndex whose term matches prevLogTerm (§5.3)
    case AppendEntries(term, _, prevLogIndex, _, _, _) if term < currentTerm || log.size < prevLogIndex =>
      sender() ! AppendEntriesResult(currentTerm, success = false)
    case AppendEntries(term, _, prevLogIndex, _, leaderCommit, entries) =>
      currentTerm = term // >= here
      nomination.cancel()

      //If commitIndex > lastApplied: increment lastApplied, apply log[lastApplied] to state machine (§5.3)
      if (commitIndex > lastApplied) {
        lastApplied = commitIndex
        //todo apply to state machine
      }

      //3. If an existing entry conflicts with a new one (same index but different terms),
      //   delete the existing entry and all that follow it (§5.3)
      val existing = log.view(prevLogIndex + 1, log.size)
      val (maybeConflicting, newEntries) = entries.splitAt(existing.size)
      val conflictIdx = Option(
        existing
          .zip(maybeConflicting)
          .indexWhere {
            case (current, incoming) => current.term != incoming.term
          }
      ).filter(_ != -1)
      conflictIdx.foreach { idx =>
        log.reduceToSize(prevLogIndex + idx)
        log ++= maybeConflicting.drop(idx)
      }

      //4. Append any new entries not already in the log
      log ++= newEntries

      //5. If leaderCommit > commitIndex, set commitIndex = min(leaderCommit, index of last new entry)
      if (leaderCommit > commitIndex) commitIndex = math.min(leaderCommit, log.size - 1)
      become(follower())
  }

  private def handleVotes(nomination: Cancellable): Receive = {
    case RequestVote(term, candidateId, lastLogIndex, lastLogTerm) =>
      //1. Reply false if term < currentTerm (§5.1)
      //2. If votedFor is null or candidateId, and candidate’s log is
      //   at least as up-to-date as receiver’s log, grant vote (§5.2, §5.4)
      nomination.cancel()
      sender() ! RequestVoteResult(currentTerm, term >= currentTerm && votedFor.forall(_ == candidateId))
      become(follower())
  }

  def follower(nomination: Cancellable =
               system.scheduler.scheduleOnce(config.electionTimeout, self, StandForElection)): Receive =
    handleAppendEntries(nomination)
      .orElse(handleVotes(nomination))
      .orElse {
      case StandForElection =>
        //election timeout elapsed, start election:
        currentTerm = currentTerm.copy(currentTerm.value + 1) //increment currentTerm
        self ! RequestVoteResult(currentTerm, voteGranted = true) //vote for self
        broadcast(RequestVote(currentTerm, id, log.size - 1, log.last.term)) //send RequestVote RPCs to all other servers
        become(candidate())
    }

  def candidate(nomination: Cancellable =
                system.scheduler.scheduleOnce(config.electionTimeout, self, StandForElection)
               ): Receive =
    follower(nomination).orElse {
      case RequestVoteResult(term, true) if term == currentTerm =>
        votes += 1
        if (votes > nodes().size / 2) {
          broadcast(AppendEntries(currentTerm, id, log.size - 1, log.last.term, commitIndex, Vector.empty)) //send initial empty AppendEntries RPCs
          (0 to nodes().size).foreach { idx =>
            nextIndex.update(idx, log.size + 1)
            matchIndex.update(idx, 0)
          }
          become(leader())
        } else become(candidate())
    }

  def leader(heartbeat: Cancellable =
             system.scheduler.scheduleOnce(config.electionTimeout, self, Heartbeat)
            ): Receive = {
    case Heartbeat =>
      nodes.iterator.zip(nextIndex.iterator).foreach {
        case (ref, index) if log.size - 1 > index =>
          val rpc = AppendEntries(
            currentTerm,
            id,
            index,
            log(index).term,
            commitIndex,
            log.view(index + 1, log.size).toVector
          )
          ref ! rpc
          become(leader())
      }
    case AppendEntriesResult(term, success) =>
      val idx = nodes().indexOf(sender())
      if (success) {
        //todo update nextIndex and matchIndex
      } else {
        nextIndex(idx) = nextIndex(idx) - 1
      }
    case c: Command =>
      log += Entry(currentTerm, c)
  }

  //todo commitIndex updates
  //todo apply to state machine?

}

final case class Id(value: String) extends AnyVal

final case class Term(value: Int) extends AnyVal with Ordered[Term] {
  override def compare(that: Term) = value.compareTo(that.value)
}

sealed trait Command

final case class SetValue(key: String, value: String) extends Command

final case class Entry(term: Term, command: Command)

object RaftActor {

  /**
    * Invoked by leader to replicate log entries (§5.3); also used as a heartbeat (§5.2)
    *
    * @param term         leader’s term
    * @param leaderId     so follower can redirect clients
    * @param prevLogIndex index of log entry immediately preceding new ones
    * @param prevLogTerm  term of prevLogIndex entry
    * @param leaderCommit leader’s commitIndex
    * @param entries      log entries to store (empty for heartbeat; may send more than one for efficiency)
    */
  final case class AppendEntries(term: Term, leaderId: Id, prevLogIndex: Int, prevLogTerm: Term, leaderCommit: Int, entries: Vector[Entry])

  /**
    * Result of AppendEntriesRPC
    *
    * @param term    currentTerm, for leader to update itself
    * @param success true if follower contained entry matching prevLogIndex and prevLogTerm
    */
  final case class AppendEntriesResult(term: Term, success: Boolean)

  /**
    * Invoked by candidates to gather votes (§5.2).
    *
    * @param term         candidate’s term
    * @param candidateId  candidate requesting vote
    * @param lastLogIndex index of candidate’s last log entry (§5.4)
    * @param lastLogTerm  term of candidate’s last log entry (§5.4)
    */
  final case class RequestVote(term: Term, candidateId: Id, lastLogIndex: Int, lastLogTerm: Term)

  /**
    * Result of RequestVoteRPC
    *
    * @param term        currentTerm, for candidate to update itself
    * @param voteGranted true means candidate received vote
    */
  final case class RequestVoteResult(term: Term, voteGranted: Boolean)

  case object StandForElection

  case object Heartbeat

}
