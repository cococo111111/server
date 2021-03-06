package konstructs

import java.util
import java.util.UUID

import scala.collection.JavaConverters._
import scala.math.min

import com.typesafe.config.{Config => TypesafeConfig, ConfigValueType}
import com.typesafe.config.ConfigException.BadValue
import akka.actor.{Actor, Props, ActorRef, Stash}
import konstructs.api._
import konstructs.api.messages.GetBlockFactory
import konstructs.plugin.{PluginConstructor, Config, ListConfig}

class KonstructingActor(universe: ActorRef, konstructs: Set[Konstruct]) extends Actor with Stash {

  universe ! GetBlockFactory.MESSAGE

  def bestMatch(pattern: Pattern, factory: BlockFactory): Option[Konstruct] = {
    konstructs.filter { k =>
      pattern.contains(k.getPattern, factory) > 0
    }.toSeq.sortWith(_.getPattern.getComplexity > _.getPattern.getComplexity).headOption
  }

  def receive = {
    case factory: BlockFactory =>
      context.become(initialized(factory))
      unstashAll()
    case _ =>
      stash()
  }

  def initialized(factory: BlockFactory): Receive = {
    case MatchPattern(pattern: Pattern) =>
      bestMatch(pattern, factory).map { k =>
        sender ! PatternMatched(k.getResult)
      }
    case KonstructPattern(pattern: Pattern) =>
      bestMatch(pattern, factory) match {
        case Some(k) =>
          val maxNumberOfStacks = min(pattern.contains(k.getPattern, factory), Stack.MAX_SIZE / k.getResult.size)
          sender ! PatternKonstructed(k.getPattern, k.getResult, maxNumberOfStacks)
        case None =>
          sender ! PatternNotKonstructed
      }
  }
}

object KonstructingActor {

  import konstructs.plugin.Plugin.nullAsEmpty

  def parseStack(config: TypesafeConfig): Stack = {
    val blockId = config.getString("id")
    val amount = if (config.hasPath("amount")) {
      config.getInt("amount")
    } else {
      1
    }
    new Stack(((0 until amount).map { i =>
      Block.create(blockId)
    }).toArray)
  }

  def parseStackTemplate(config: TypesafeConfig): StackTemplate = {
    if (config.hasPath("id")) {
      val blockId = config.getString("id")
      val amount = if (config.hasPath("amount")) {
        config.getInt("amount")
      } else {
        1
      }
      new StackTemplate(BlockOrClassId.fromString(blockId), amount)
    } else {
      null
    }
  }

  def parsePatternTemplate(config: TypesafeConfig): PatternTemplate = {
    if (config.hasPath("stack")) {
      new PatternTemplate(Array(parseStackTemplate(config.getConfig("stack"))), 1, 1)
    } else {
      if (config.getValue("stacks").valueType() == ConfigValueType.OBJECT) {
        throw new BadValue("stacks", "'stacks' does not expect object. Did you mean 'stack'?")
      }
      val rows = config.getInt("rows")
      val columns = config.getInt("columns")
      val stacks = util.Arrays.copyOf(
        config.getConfigList("stacks").asScala.map(parseStackTemplate).toArray,
        rows * columns
      )
      new PatternTemplate(stacks, rows, columns)
    }
  }

  def parseKonstructs(config: TypesafeConfig): Set[Konstruct] = {
    val konstructs = config.root.entrySet.asScala.map { e =>
      config.getConfig(e.getKey)
    }
    (for (konstruct <- konstructs) yield {
      val pattern = parsePatternTemplate(konstruct.getConfig("match"))
      val result = parseStack(konstruct.getConfig("result"))
      new Konstruct(pattern, result)
    }) toSet
  }

  @PluginConstructor
  def props(
      name: String,
      universe: ActorRef,
      @Config(key = "konstructs") konstructs: TypesafeConfig
  ): Props =
    Props(classOf[KonstructingActor], universe, parseKonstructs(konstructs))
}

class KonstructingViewActor(player: ActorRef,
                            universe: ActorRef,
                            inventoryId: UUID,
                            inventoryView: InventoryView,
                            konstructingView: InventoryView,
                            resultView: InventoryView)
    extends Actor
    with Stash {

  universe ! GetBlockFactory.MESSAGE

  val EmptyInventory = Inventory.createEmpty(0)
  var konstructing = Inventory.createEmpty(konstructingView.getRows * konstructingView.getColumns)
  var result = Inventory.createEmpty(resultView.getRows * resultView.getColumns)
  var factory: BlockFactory = null

  private def view(inventory: Inventory) =
    View.EMPTY.add(inventoryView, inventory).add(konstructingView, konstructing).add(resultView, result)

  private def updateKonstructing(k: Inventory) {
    konstructing = k
    result = Inventory.createEmpty(1)
    val p = konstructing.getPattern(konstructingView)
    if (p != null)
      universe ! MatchPattern(p)
  }

  def receive = {
    case f: BlockFactory =>
      factory = f
      if (inventoryId != null) {
        universe ! GetInventory(inventoryId)
      } else {
        context.become(ready(EmptyInventory))
        player ! ConnectView(self, view(EmptyInventory))
      }
    case GetInventoryResponse(_, Some(inventory)) =>
      context.become(ready(inventory))
      player ! ConnectView(self, view(inventory))
    case _ =>
      context.stop(self)
  }

  def awaitInventory: Receive = {
    case GetInventoryResponse(_, Some(inventory)) =>
      context.become(ready(inventory))
      player ! UpdateView(view(inventory))
      unstashAll()
    case CloseInventory =>
      context.stop(self)
    case _ =>
      stash()
  }

  def awaitKonstruction(inventory: Inventory, amount: StackAmount): Receive = {
    case PatternKonstructed(pattern, stack, number) =>
      context.become(ready(inventory))
      val toKonstruct = amount match {
        case StackAmount.ALL =>
          number
        case StackAmount.HALF =>
          Math.max(number / 2, 1)
        case StackAmount.ONE =>
          1
      }

      val newKonstructing = konstructing.remove(pattern, factory, toKonstruct)
      if (newKonstructing != null)
        updateKonstructing(newKonstructing)
      player ! ReceiveStack(Stack.createOfSize(stack.getTypeId, toKonstruct * stack.size))
      player ! UpdateView(view(inventory))
      unstashAll()
    case CloseInventory =>
      context.stop(self)
    case _ =>
      stash()
  }

  def ready(inventory: Inventory): Receive = {
    case r: ReceiveStack =>
      player ! r
    case PatternMatched(stack) =>
      result = result.withSlot(0, stack)
      player ! UpdateView(view(inventory))
    case PutViewStack(stack, to) =>
      if (inventoryView.contains(to)) {
        context.become(awaitInventory)
        universe.forward(PutStack(inventoryId, inventoryView.translate(to), stack))
        universe ! GetInventory(inventoryId)
      } else if (konstructingView.contains(to)) {
        val index = konstructingView.translate(to)
        val oldStack = konstructing.getStack(index)
        if (oldStack != null) {
          if (oldStack.acceptsPartOf(stack)) {
            val r = oldStack.acceptPartOf(stack)
            sender ! ReceiveStack(r.getGiving)
            updateKonstructing(konstructing.withSlot(index, r.getAccepting()))
          } else {
            updateKonstructing(konstructing.withSlot(index, stack))
            sender ! ReceiveStack(oldStack)
          }
        } else {
          updateKonstructing(konstructing.withSlot(index, stack))
          sender ! ReceiveStack(null)
        }
        player ! UpdateView(view(inventory))
      } else {
        sender ! ReceiveStack(stack)
      }
    case RemoveViewStack(from, amount) =>
      if (inventoryView.contains(from)) {
        context.become(awaitInventory)
        universe.forward(RemoveStack(inventoryId, inventoryView.translate(from), amount))
        universe ! GetInventory(inventoryId)
      } else if (konstructingView.contains(from)) {
        val stack = konstructing.getStack(konstructingView.translate(from))
        updateKonstructing(konstructing.withSlot(konstructingView.translate(from), stack.drop(amount)))
        sender ! ReceiveStack(stack.take(amount))
        player ! UpdateView(view(inventory))
      } else if (resultView.contains(from)) {
        val pattern = konstructing.getPattern(konstructingView)
        if (pattern != null) {
          if (!result.isEmpty) {
            context.become(awaitKonstruction(inventory, amount))
            universe ! KonstructPattern(pattern)
          } else {
            sender ! ReceiveStack(null)
          }
        } else {
          sender ! ReceiveStack(null)
        }
      }
    case CloseInventory =>
      context.stop(self)
  }

}

object KonstructingViewActor {
  def props(player: ActorRef,
            universe: ActorRef,
            inventoryId: UUID,
            inventoryView: InventoryView,
            konstructingView: InventoryView,
            resultView: InventoryView): Props =
    Props(classOf[KonstructingViewActor], player, universe, inventoryId, inventoryView, konstructingView, resultView)
}
