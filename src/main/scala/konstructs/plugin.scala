package konstructs.plugin

import java.lang.reflect.{ Method, Type, Modifier }
import java.io.File
import scala.concurrent.Future
import scala.collection.JavaConverters._
import akka.util.Timeout
import akka.actor.{ Props, ActorSystem, ActorRef, Actor, ActorSelection, Stash }
import com.typesafe.config.{ Config => TypesafeConfig, ConfigException }

object Plugin {
  val StaticParameters = 2
}

case class PluginConfigParameterMeta(name: String, configType: Class[_], listType: Option[Class[_]] = None)

case class PluginConfigMeta(method: Method, parameters: Seq[PluginConfigParameterMeta])

object PluginConfigMeta {
  def apply(m: Method): PluginConfigMeta = {
    val annotations = m
      .getParameterAnnotations
      .flatMap(_.filter { a => a.isInstanceOf[Config] || a.isInstanceOf[ListConfig] } )
    val parameters = m.getParameterTypes.drop(Plugin.StaticParameters).zip(annotations).map {
      case (t, c: Config) =>
        PluginConfigParameterMeta(c.key, t)
      case (t, c: ListConfig) =>
        PluginConfigParameterMeta(c.key, c.elementType, Some(t))
    }
    apply(m, parameters)
  }
}

case class PluginMeta(configs: Seq[PluginConfigMeta])

object PluginMeta {

  private def allParametersAreAnnotated(m: Method): Boolean =
    m.getParameterAnnotations.filter(_.exists { a => a.isInstanceOf[Config] || a.isInstanceOf[ListConfig] }).size == m.getParameterTypes.size - Plugin.StaticParameters

  def apply(className: String): PluginMeta = {
    val clazz = Class.forName(className)
    val configs = clazz
      .getMethods
      .filter(_.getReturnType == classOf[Props])
      .filter { m => Modifier.isStatic(m.getModifiers) }
      .filter(_.getParameterTypes()(0) == classOf[String])
      .filter(_.getParameterTypes()(1) == classOf[ActorRef])
      .filter(_.getAnnotations.exists(_.isInstanceOf[PluginConstructor]))
      .filter(allParametersAreAnnotated)
      .map(PluginConfigMeta.apply)
    apply(configs)
  }

}

case class Dependencies(names: Seq[String], t: Class[_])

object Dependencies {
  def apply(dep: String): Dependencies = apply(Seq(dep), classOf[ActorRef])
}

case class ConfiguredPlugin(name: String, method: Method,
                            args: Seq[Either[Object, Dependencies]]) {

  val dependencyEdges =
    args.collect {
      case Right(deps) => deps.names.map((name, _))
    } flatten

}

class PluginLoaderActor(config: TypesafeConfig) extends Actor {
  import scala.collection.JavaConverters._
  import PluginLoaderActor._
  import context.dispatcher
  import UniverseProxyActor.SetUniverse

  implicit val selectionTimeout = Timeout(1, java.util.concurrent.TimeUnit.SECONDS)

  val StringType = classOf[String]
  val FileType = classOf[File]
  val ActorRefType = classOf[ActorRef]
  val SeqType = classOf[Seq[_]]
  val ListType = classOf[java.util.List[_]]

  val universeProxy = context.actorOf(UniverseProxyActor.props(), "universe-proxy")

  private def listType(t: Class[_], list: java.util.List[_ <: AnyRef]): Object = t match {
    case SeqType => list.asScala.toSeq
    case ListType => list
    case ActorRefType => list.asScala.head
  }


  def configurePlugin(name: String, config: TypesafeConfig, c: PluginConfigMeta): ConfiguredPlugin = {
    val args: Seq[Either[Object, Dependencies]] = c.parameters.map { p =>
      p.configType match {
        case StringType => if(p.listType.isDefined) {
          Left(listType(p.listType.get, config.getStringList(p.name)))
        } else {
          Left(config.getString(p.name))
        }
        case FileType => if(p.listType.isDefined) {
          Left(listType(p.listType.get, config.getStringList(p.name).asScala.map(new File(_)).asJava))
        } else {
          Left(new File(config.getString(p.name)))
        }
        case ActorRefType => if(p.listType.isDefined) {
          Right(Dependencies(config.getStringList(p.name).asScala.toSeq, p.listType.get))
        } else {
          Right(Dependencies(config.getString(p.name)))
        }
      }
    }
    val staticArgs =
      Seq(Left[Object, Dependencies](name), Left[Object, Dependencies](universeProxy))
    ConfiguredPlugin(name, c.method,
      staticArgs ++ args)
  }

  def configurePlugin(name: String, config: TypesafeConfig, meta: PluginMeta): ConfiguredPlugin = {
    for(c <- meta.configs.sortBy(_.parameters.size).reverse) {
      try {
        return configurePlugin(name, config, c)
      } catch {
        case e: ConfigException.Missing =>
      }
    }
    println(s"Valid configurations: ${meta.configs}")
    throw new Exception(s"No valid plugin constructor found for $name")
  }

  def invokePlugins(plugins: List[ConfiguredPlugin]) {
    plugins match {
      case head :: tail =>
        val args = Future.sequence(head.args.map {
          case Right(d) =>
            Future.sequence(d.names.map { dep =>
              ActorSelection(self, dep).resolveOne
            }).map { as => listType(d.t, as.toList.asJava) }
          case Left(obj) => Future.successful(obj)
        })
        args.onFailure {
          case e => println(s"Failed to start plugin ${head.name} due to $e")
        }
        for(a <- args) {
          val props = head.method.invoke(null, a: _*).asInstanceOf[Props]
          val actor = context.actorOf(props, head.name)
          println(s"Started plugin ${head.name}")
          if(head.name == "universe") {
            println("Universe started, updating proxy")
            universeProxy ! SetUniverse(actor)
          }
          invokePlugins(tail)
        }
      case _ => Nil
    }
  }

  def receive = {
    case Start =>
      val objs = config.root().entrySet.asScala.filter(_.getValue.valueType == com.typesafe.config.ConfigValueType.OBJECT)
      val plugins = for(e <- objs) yield {
        val name = e.getKey
        val plugin = config.getConfig(name)
        val clazz = plugin.getString("class")
        val meta = PluginMeta(clazz)

        val pluginConf = configurePlugin(name, plugin, meta)
        println(s"Validated configuration for $name")
        pluginConf
      }

      val pluginMap = plugins.map { p =>  (p.name, p) }.toMap

      val pluginEdges = plugins.flatMap(_.dependencyEdges)

      println(s"Plugin dependencies: $pluginEdges")

      println("Resolving dependency order ...")
      val sortedPlugins = tsort(pluginEdges).map(pluginMap).toSeq.reverse
      val allPlugins = sortedPlugins ++ (plugins.toSet &~ sortedPlugins.toSet).toSeq
      println(s"Loading plugins in dependency order: ${allPlugins.map(_.name)}")
      invokePlugins(allPlugins.toList)
  }
}

object PluginLoaderActor {
  case object Start
  import scala.annotation.tailrec

  def tsort[A](edges: Traversable[(A, A)]): Iterable[A] = {
    @tailrec
    def tsort(toPreds: Map[A, Set[A]], done: Iterable[A]): Iterable[A] = {
        val (noPreds, hasPreds) = toPreds.partition { _._2.isEmpty }
        if (noPreds.isEmpty) {
            if (hasPreds.isEmpty) done else sys.error(hasPreds.toString)
        } else {
            val found = noPreds.map { _._1 }
            tsort(hasPreds.mapValues { _ -- found }, done ++ found)
        }
    }

    val toPred = edges.foldLeft(Map[A, Set[A]]()) { (acc, e) =>
        acc + (e._1 -> acc.getOrElse(e._1, Set())) + (e._2 -> (acc.getOrElse(e._2, Set()) + e._1))
    }
    tsort(toPred, Seq())
  }

  def props(config: TypesafeConfig) = Props(classOf[PluginLoaderActor], config)
}

class UniverseProxyActor extends Actor with Stash {
  import UniverseProxyActor.SetUniverse

  def receive = {
    case SetUniverse(universe) =>
      unstashAll()
      context.become(ready(universe))
    case _ =>
      stash()
  }

  def ready(universe: ActorRef): Receive = {
    case o =>
      universe.forward(o)
  }

}

object UniverseProxyActor {
  case class SetUniverse(universe: ActorRef)
  def props() = Props(classOf[UniverseProxyActor])
}