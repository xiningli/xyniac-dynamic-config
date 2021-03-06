package com.xyniac.abstractconfig

import java.nio.file.{Files, Paths}
import java.util.concurrent.locks.{ReadWriteLock, ReentrantReadWriteLock}
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}

import com.google.gson.{Gson, JsonObject}
import com.xyniac.environment.Spec
import org.json4s._
import org.json4s.native.JsonMethods._
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, blocking}
import scala.io.Source
import scala.util.{Failure, Success, Try}
import java.io.{BufferedReader, InputStream, InputStreamReader}
import java.util.stream.Collectors

/**
 * Created by Xining Li on May/10/2020
 */

object AbstractDynamicConfig {

  def getResourceFileAsString(fileName: String): String = {
    val stream: InputStream = Thread
      .currentThread
      .getContextClassLoader
      .getResourceAsStream(fileName)
    val br = new BufferedReader(new InputStreamReader(stream))
    val res = br.lines().collect(Collectors.joining("\n"))
    stream.close()
    res
  }

  val confDirName: String = "conf"

  private val registry = new scala.collection.mutable.HashMap[String, AbstractDynamicConfig]
  private val logger = LoggerFactory.getLogger(this.getClass)
  private implicit val formats: DefaultFormats.type = DefaultFormats
  private val lock: ReadWriteLock = new ReentrantReadWriteLock
  private val gson: Gson = new Gson()
  private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor
  private val coldDeployedInitialDelay = getColdConfig("com.xyniac.abstractconfig.RemoteConfig$").get("initialDelay").getAsLong
  private val dynamicConfigEnabled: Boolean = Try {
    getColdConfig("com.xyniac.abstractconfig.RemoteConfig$").get("dynamicConfigEnabled").getAsBoolean
  } match {
    case Success(value) => value
    case Failure(exception) => {
      logger.error("Unable to load the dynamicConfigEnabled flag, setting to false. ")
      false
    }
  }


  private val task: java.util.concurrent.Callable[String] = new java.util.concurrent.Callable[String]() {
    def call(): String = {

      val fileSystem = Future(RemoteConfig.getFileSystem)
      logger.info("loading the config file system")

      fileSystem.onComplete {
        case Success(fs) => if (fs.isOpen) {
          Try {
            logger.info(s"loaded the config file system $fs")
            logger.info(fileSystem.toString)

            val parallelFuturesKeyConfigString = registry.keys.map(key => {
              logger.info(s"scanning the updated config for $key")
              val jsonFileName = key
              val hotDeployedDefaultConfigFuture =
                Future(blocking {
                  parse(((Files.newInputStream(fs.getPath(AbstractDynamicConfig.confDirName, jsonFileName)))))
                })
                  .recover { case e: Exception => parse("{}") }

              val hotDeployedEnvConfigFuture =
                Future(blocking {
                  parse(Files.newInputStream(fs.getPath(AbstractDynamicConfig.confDirName, Spec.environment, jsonFileName)))
                })
                  .recover { case e: Exception => parse("{}") }

              val hotDeployedIaasConfigFuture =
                Future(blocking {
                  parse(Files.newInputStream(fs.getPath(AbstractDynamicConfig.confDirName, Spec.environment, Spec.iaas, jsonFileName)))
                })
                  .recover { case e: Exception => parse("{}") }

              val hotDeployedRegionConfigFuture =
                Future(blocking {
                  parse(Files.newInputStream(fs.getPath(AbstractDynamicConfig.confDirName, Spec.environment, Spec.iaas, Spec.region, jsonFileName)))
                })
                  .recover { case e: Exception => parse("{}") }

              val hotDeployedConfigCombinedFuture: Future[String] = for {
                hotDeployedDefaultConfig <- hotDeployedDefaultConfigFuture
                hotDeployedEnvConfig <- hotDeployedEnvConfigFuture
                hotDeployedIaasConfig <- hotDeployedIaasConfigFuture
                hotDeployedRegionConfig <- hotDeployedRegionConfigFuture
              } yield pretty(render(hotDeployedDefaultConfig merge hotDeployedEnvConfig merge hotDeployedIaasConfig merge hotDeployedRegionConfig))
              (key, hotDeployedConfigCombinedFuture)
            })

            val configToReplace = parallelFuturesKeyConfigString.map(futureKeyConfig => {
              val value = Await.result(futureKeyConfig._2, RemoteConfig.getDelay.milliseconds)
              (futureKeyConfig._1, value)
            }).map(keyConfigString => (keyConfigString._1, gson.fromJson(keyConfigString._2, classOf[JsonObject])))
              .filterNot(keyConfigObject => registry(keyConfigObject._1).getConfigJson.equals(keyConfigObject._2))


            if (configToReplace.nonEmpty) {

              logger.info("start updating the hotDeployedConfig pointer")
              try {
                lock.writeLock().lock()
                configToReplace.foreach(nc => {
                  val obsoletedConfig = registry.get(nc._1)
                  obsoletedConfig.get.hotDeployedConfig = nc._2
                })
              } catch {
                case e: Exception => {
                  logger.error("error on replacing the config due to: ", e)
                }
                case error: Error => {
                  logger.error("SEVERE ERROR OCCURRED! ", error)
                }
              } finally {
                lock.writeLock().unlock()
                logger.info("hotDeployedConfig pointer updating finished, closing the file system")
                fs.close()
              }

            }
          } match {
            case Success(s) => {
              logger.info("refreshing task successful")
              scheduler.schedule(task, RemoteConfig.getDelay, TimeUnit.MILLISECONDS)
            }
            case Failure(e) => {
              logger.error(s"error loading the config from file system $fs", e)
              scheduler.schedule(task, coldDeployedInitialDelay, TimeUnit.MILLISECONDS)
            }
          }
        } else {
          logger.info(s"remote file system $fs is closed, retrying")
          scheduler.schedule(task, RemoteConfig.getDelay, TimeUnit.MILLISECONDS)
        }
        case Failure(e) => {
          logger.error(s"error initiating the file system $fileSystem instance, reschedule remote config reloading task", e)
          scheduler.schedule(task, coldDeployedInitialDelay, TimeUnit.MILLISECONDS)
        }
      }
      "triggered the task handler"
    }
  }

  if (dynamicConfigEnabled) {
    scheduler.schedule(task, coldDeployedInitialDelay, TimeUnit.MILLISECONDS)
  }

  def checkAllConfig(): JsonObject = {
    val result = new JsonObject
    registry.keys.foreach(key => result.add(key, registry(key).getConfigJson))
    result
  }


  def getColdConfig(jsonFileName: String): JsonObject = {

    val coldDeployedDefaultConfig: String = Try {
      getResourceFileAsString(Paths.get(AbstractDynamicConfig.confDirName, jsonFileName).toString)
    } match {
      case Success(s) => s
      case Failure(e) => "{}"
    }

    val coldDeployedEnvConfig: String = Try {
      getResourceFileAsString(Paths.get(AbstractDynamicConfig.confDirName, Spec.environment, jsonFileName).toString)
    } match {
      case Success(s) => s
      case Failure(e) => "{}"
    }

    val coldDeployedIaasConfig: String = Try(
      getResourceFileAsString(Paths.get(AbstractDynamicConfig.confDirName, Spec.environment, Spec.iaas, jsonFileName).toString)
    ) match {
      case Success(s) => s
      case Failure(e) => "{}"
    }

    val coldDeployedRegionConfig: String = Try(
      getResourceFileAsString(Paths.get(AbstractDynamicConfig.confDirName, Spec.environment, Spec.iaas, Spec.region, jsonFileName).toString)
    ) match {
      case Success(s) => s
      case Failure(e) => "{}"
    }

    val coldDeployedConfigJson4j = parse(coldDeployedDefaultConfig) merge parse(coldDeployedEnvConfig) merge parse(coldDeployedIaasConfig) merge parse(coldDeployedRegionConfig)

    val renderedConfigJson = pretty(render(coldDeployedConfigJson4j))
    val coldDeployedConfig = AbstractDynamicConfig.gson.fromJson(renderedConfigJson, classOf[JsonObject])

    coldDeployedConfig
  }

  logger.info("Abstract Config Singleton Initializing")
}

abstract class AbstractDynamicConfig {


  val jsonFileName: String = getKey

  if (AbstractDynamicConfig.registry.contains(jsonFileName)) {
    throw new IllegalStateException(s"$jsonFileName already registered")
  }
  AbstractDynamicConfig.registry.put(jsonFileName, this)

  private val coldDeployedConfig = AbstractDynamicConfig.getColdConfig(jsonFileName)
  private val remoteConfig = RemoteConfig
  private[abstractconfig] var hotDeployedConfig: JsonObject = null

  def setProperty[T](key: String, value: T): Unit = {
    AbstractDynamicConfig.lock.writeLock().lock()
    try {
      if (hotDeployedConfig == null) {
        hotDeployedConfig = new JsonObject()
      }
      hotDeployedConfig.add(key, AbstractDynamicConfig.gson.toJsonTree(value))
    } finally {
      AbstractDynamicConfig.lock.writeLock().unlock()
    }
  }

  def getProperty[T](key: String, classType: Class[T], defaultValue: Option[T] = Option.empty): T = {
    AbstractDynamicConfig.lock.readLock.lock()
    try {
      val jsonNode = if (hotDeployedConfig != null) hotDeployedConfig.get(key) else coldDeployedConfig.get(key)
      AbstractDynamicConfig.gson.fromJson(jsonNode, classType)
    } catch {
      case _: Exception => defaultValue match {
        case None => throw new IllegalArgumentException(s"property $key is defined nowhere")
        case Some(t) => t
      }
    } finally {
      AbstractDynamicConfig.lock.readLock.unlock()
    }

  }

  def getConfigJson: JsonObject = {
    if (hotDeployedConfig == null) {
      coldDeployedConfig
    } else {
      AbstractDynamicConfig.lock.readLock.lock()
      val res = hotDeployedConfig.deepCopy()
      AbstractDynamicConfig.lock.readLock.unlock()
      res
    }
  }

  def getKey: String = this.getClass.getName
}


