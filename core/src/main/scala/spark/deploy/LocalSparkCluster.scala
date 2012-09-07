package spark.deploy

import akka.actor.{ActorRef, Props, Actor, ActorSystem, Terminated}

import spark.deploy.worker.Worker
import spark.deploy.master.Master
import spark.util.AkkaUtils
import spark.{Logging, Utils}

import scala.collection.mutable.ArrayBuffer

class LocalSparkCluster(numSlaves : Int, coresPerSlave : Int, 
  memoryPerSlave : Int) extends Logging {
  
  val threadPool = Utils.newDaemonFixedThreadPool(numSlaves + 1)
  val localIpAddress = Utils.localIpAddress
  
  var masterActor : ActorRef = _
  var masterActorSystem : ActorSystem = _
  var masterPort : Int = _
  var masterUrl : String = _
  
  val slaveActorSystems = ArrayBuffer[ActorSystem]()
  val slaveActors = ArrayBuffer[ActorRef]()
  
  def start() : String = {

    logInfo("Starting a local Spark cluster with " + numSlaves + " slaves.")

    /* Start the Master */
    val (masterActorSystem, masterPort) = AkkaUtils.createActorSystem("sparkMaster", localIpAddress, 0)
    masterUrl = "spark://" + localIpAddress + ":" + masterPort
    threadPool.execute(new Runnable {
      def run() {
        val actor = masterActorSystem.actorOf(
          Props(new Master(localIpAddress, masterPort, 8080)), name = "Master")
        masterActor = actor
        masterActorSystem.awaitTermination()
      }
    })

    /* Start the Slaves */
    (1 to numSlaves).foreach { slaveNum =>
      val (actorSystem, boundPort) = 
        AkkaUtils.createActorSystem("sparkWorker" + slaveNum, localIpAddress, 0)
      slaveActorSystems += actorSystem
      threadPool.execute(new Runnable {
        def run() {  
          val actor = actorSystem.actorOf(
            Props(new Worker(localIpAddress, boundPort, 8080 + slaveNum, coresPerSlave, memoryPerSlave, masterUrl)),
            name = "Worker")
          slaveActors += actor
          actorSystem.awaitTermination()
        }
      })
    }

    return masterUrl
  }

  def stop() {
    logInfo("Shutting down local Spark cluster.")
    masterActorSystem.shutdown()
    slaveActorSystems.foreach(_.shutdown())
  }
  
  
}