package com.adendamedia

import skuber._
import skuber.json.apps.format._
import com.typesafe.config.ConfigFactory
import skuber.apps.StatefulSet
import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor._

import scala.concurrent.Future

object Kubernetes {
  def props = Props(new Kubernetes)

  case object ScaleUp

  val k8s = k8sInit
  private val k8sConfig = ConfigFactory.load().getConfig("kubernetes")
  private val statefulSetName = k8sConfig.getString("statefulset-name")
}

class Kubernetes extends Actor {
  import Kubernetes._
  import scala.concurrent.ExecutionContext.Implicits.global

  def receive = {
    case ScaleUp => scaleUp
  }

  def scaleUp = {

    val resource: Future[StatefulSet] = k8s get[StatefulSet] statefulSetName
    resource map {
      case ss: StatefulSet =>
        println(s"StatefulSet Status: ${ss.status}")
        val currentReplicas = ss.spec.get.replicas
        println(s"Current replicas are ${currentReplicas}")
    }
  }

}
