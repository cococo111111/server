package konstructs

import scala.collection.JavaConverters._
import scala.collection.mutable
import akka.actor.{Actor, ActorRef, Props}

import konstructs.api.{Position, BlockFactory, Box, BlockTypeId, Block, Orientation}
import konstructs.api.messages.{
  BoxQuery,
  BoxShapeQuery,
  BoxQueryResult,
  BoxShapeQueryResult,
  ViewBlock,
  ReplaceBlocks,
  ReplaceBlock,
  DamageBlockWithBlock,
  InteractTertiary
}
import konstructs.shard.{ShardActor, ShardPosition, ChunkPosition, ChunkData, BoxChunking, Light}

object Db {
  val ChunkSize = 32
  val ShardSize = 8
}

class DbActor(universe: ActorRef,
              generator: ActorRef,
              binaryStorage: ActorRef,
              jsonStorage: ActorRef,
              blockUpdateEvents: Seq[ActorRef],
              blockFactory: BlockFactory,
              tertiaryInteractionFilters: Seq[ActorRef])
    extends Actor {
  import DbActor._

  def shardActorId(r: ShardPosition) = s"shard-${r.m}-${r.n}-${r.o}"

  def getShardActor(pos: Position): ActorRef =
    getShardActor(ShardPosition(pos))

  def getShardActor(chunk: ChunkPosition): ActorRef =
    getShardActor(ShardPosition(chunk))

  def getShardActor(shard: ShardPosition): ActorRef = {
    val rid = shardActorId(shard)
    context.child(rid) match {
      case Some(a) => a
      case None =>
        context.actorOf(ShardActor.props(self,
                                         shard,
                                         binaryStorage,
                                         jsonStorage,
                                         blockUpdateEvents,
                                         generator,
                                         blockFactory,
                                         tertiaryInteractionFilters,
                                         universe),
                        rid)
    }
  }

  def receive = {
    case i: InteractPrimaryUpdate =>
      getShardActor(i.position) forward i
    case i: InteractSecondaryUpdate =>
      getShardActor(i.position) forward i
    case i: InteractTertiaryUpdate =>
      getShardActor(i.message.getPosition) forward i
    case r: ReplaceBlock =>
      getShardActor(r.getPosition) forward r
    case v: ViewBlock =>
      getShardActor(v.getPosition) forward v
    case s: SendBlocks =>
      getShardActor(s.chunk) forward s
    case d: DamageBlockWithBlock =>
      getShardActor(d.getToDamage) forward d
    case q: BoxQuery =>
      val chunkBoxes = BoxChunking.chunked(q.getBox)
      val resultActor = context.actorOf(BoxQueryResultActor.props(sender, Left(q), chunkBoxes, blockFactory))
      chunkBoxes.foreach { box =>
        getShardActor(box.getFrom).tell(new BoxQuery(box), resultActor)
      }
    case q: BoxShapeQuery =>
      val chunkBoxes = BoxChunking.chunked(q.getBox.getBox)
      val resultActor = context.actorOf(BoxQueryResultActor.props(sender, Right(q), chunkBoxes, blockFactory))
      chunkBoxes.foreach { box =>
        getShardActor(box.getFrom).tell(new BoxQuery(box), resultActor)
      }
    case r: ReplaceBlocks =>
      for ((chunk, blocks) <- splitList[BlockTypeId](r.getBlocks)) {
        getShardActor(chunk) forward ShardActor.ReplaceBlocks(chunk, r.getFilter, blocks)
      }
    case b: BlockList =>
      universe ! b
    case c: ChunkUpdate =>
      universe ! c
    case r: Light.RefreshLight =>
      getShardActor(r.chunk) forward r
    case r: Light.RefreshAmbientLight =>
      getShardActor(r.chunk) forward r
    case f: Light.FloodLight =>
      getShardActor(f.chunk) forward f
    case f: Light.FloodAmbientLight =>
      getShardActor(f.chunk) forward f
    case l: Light.RemoveLight =>
      getShardActor(l.chunk) forward l
    case l: Light.RemoveAmbientLight =>
      getShardActor(l.chunk) forward l
  }
}

object DbActor {
  case class SendBlocks(chunk: ChunkPosition)
  case class BlockList(chunk: ChunkPosition, data: ChunkData)
  case class ChunkUpdate(chunk: ChunkPosition, data: ChunkData)

  case class InteractPrimaryUpdate(position: Position, block: Block)
  case class InteractSecondaryUpdate(position: Position, orientation: Orientation, block: Block)
  case class InteractTertiaryUpdate(filters: Seq[ActorRef], message: InteractTertiary)

  def splitList[T](placed: java.util.Map[Position, T]): Map[ChunkPosition, Map[Position, T]] = {
    val shards = mutable.HashMap[ChunkPosition, mutable.Map[Position, T]]()

    for ((position, i) <- placed.asScala) {
      val pos = ChunkPosition(position)
      val map: mutable.Map[Position, T] =
        shards.getOrElse(pos, mutable.HashMap[Position, T]())
      map += position -> i
      shards += pos -> map
    }
    (shards.map {
      case (k, v) =>
        k -> v.toMap
    }).toMap
  }

  def props(universe: ActorRef,
            generator: ActorRef,
            binaryStorage: ActorRef,
            jsonStorage: ActorRef,
            blockUpdateEvents: Seq[ActorRef],
            blockFactory: BlockFactory,
            tertiaryInteractionFilters: Seq[ActorRef]) =
    Props(classOf[DbActor],
          universe,
          generator,
          binaryStorage,
          jsonStorage,
          blockUpdateEvents,
          blockFactory,
          tertiaryInteractionFilters)
}

class BoxQueryResultActor(initiator: ActorRef,
                          blockFactory: BlockFactory,
                          query: Either[BoxQuery, BoxShapeQuery],
                          boxes: Set[Box])
    extends Actor {
  var receivedBoxes: Set[BoxQueryResult] = Set()

  private val box = query match {
    case Left(q) => q.getBox
    case Right(q) => q.getBox.getBox
  }

  def receive = {
    case r: BoxQueryResult =>
      receivedBoxes += r
      if (receivedBoxes.map(_.getBox) == boxes) {
        val data = new Array[BlockTypeId](box.getNumberOfBlocks)
        for (subData <- receivedBoxes) {
          for ((position, typeId) <- subData.getAsMap.asScala) {
            data(box.arrayIndex(position)) = typeId
          }
        }
        query match {
          case Left(q) =>
            initiator ! new BoxQueryResult(q.getBox, data)
          case Right(q) =>
            initiator ! new BoxShapeQueryResult(q.getBox, data)
        }
        context.stop(self)
      }
  }

}

object BoxQueryResultActor {
  def props(initiator: ActorRef, query: Either[BoxQuery, BoxShapeQuery], boxes: Set[Box], blockFactory: BlockFactory) =
    Props(classOf[BoxQueryResultActor], initiator, blockFactory, query, boxes)
}
