/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.deploy.worker

import java.io._

import scala.collection.JavaConverters._

import com.google.common.base.Charsets.UTF_8
import com.google.common.io.Files
import org.apache.spark.deploy.DeployMessages.ExecutorStateChanged
import org.apache.spark.deploy.{ApplicationDescription, ExecutorState}
import org.apache.spark.rpc.RpcEndpointRef
import org.apache.spark.util.{ShutdownHookManager, Utils}
import org.apache.spark.util.logging.FileAppender
import org.apache.spark.{Logging, SecurityManager, SparkConf}

/**
 * Manages the execution of one executor process.
 * This is currently only used in standalone mode.
 */
private[deploy] class ExecutorRunner(
    val appId: String,
    val execId: Int,
    val appDesc: ApplicationDescription,
    val cores: Int,
    val memory: Int,
    val worker: RpcEndpointRef,
    val workerId: String,
    val host: String,
    val webUiPort: Int,
    val publicAddress: String,
    val sparkHome: File,
    val executorDir: File,
    val workerUrl: String,
    conf: SparkConf,
    val appLocalDirs: Seq[String],
    @volatile var state: ExecutorState.Value)
  extends Logging {

  private val fullId = appId + "/" + execId
  private var workerThread: Thread = null
  private var process: Process = null
  private var stdoutAppender: FileAppender = null
  private var stderrAppender: FileAppender = null

  // Timeout to wait for when trying to terminate an executor.
  private val EXECUTOR_TERMINATE_TIMEOUT_MS = 10 * 1000

  // NOTE: This is now redundant with the automated shut-down enforced by the Executor. It might
  // make sense to remove this in the future.
  private var shutdownHook: AnyRef = null

  /**
    * 启动ExecutorRunner
    */
  private[worker] def start() {
    //创建worker线程
    workerThread = new Thread("ExecutorRunner for " + fullId) {
      //调用fetchAndRunExecutor方法
      override def run() { fetchAndRunExecutor() }
    }
    //启动worker线程
    workerThread.start()
    // Shutdown hook that kills actors on shutdown.
    // 创建Shutdownhook线程
    // 用于worker关闭时，杀掉executor
    shutdownHook = ShutdownHookManager.addShutdownHook { () =>
      // It's possible that we arrive here before calling `fetchAndRunExecutor`, then `state` will
      // be `ExecutorState.RUNNING`. In this case, we should set `state` to `FAILED`.
      if (state == ExecutorState.RUNNING) {
        state = ExecutorState.FAILED
      }
      killProcess(Some("Worker shutting down")) }
  }

  /**
   * Kill executor process, wait for exit and notify worker to update resource status.
   *
   * @param message the exception message which caused the executor's death
   */
  private def killProcess(message: Option[String]) {
    var exitCode: Option[Int] = None
    if (process != null) {
      logInfo("Killing process!")
      if (stdoutAppender != null) {
        stdoutAppender.stop()
      }
      if (stderrAppender != null) {
        stderrAppender.stop()
      }
      exitCode = Utils.terminateProcess(process, EXECUTOR_TERMINATE_TIMEOUT_MS)
      if (exitCode.isEmpty) {
        logWarning("Failed to terminate process: " + process +
          ". This process will likely be orphaned.")
      }
    }
    try {
      worker.send(ExecutorStateChanged(appId, execId, state, message, exitCode))
    } catch {
      case e: IllegalStateException => logWarning(e.getMessage(), e)
    }
  }

  /** Stop this executor runner, including killing the process it launched */
  private[worker] def kill() {
    if (workerThread != null) {
      // the workerThread will kill the child process when interrupted
      workerThread.interrupt()
      workerThread = null
      state = ExecutorState.KILLED
      try {
        ShutdownHookManager.removeShutdownHook(shutdownHook)
      } catch {
        case e: IllegalStateException => None
      }
    }
  }

  /** Replace variables such as {{EXECUTOR_ID}} and {{CORES}} in a command argument passed to us */
  private[worker] def substituteVariables(argument: String): String = argument match {
    case "{{WORKER_URL}}" => workerUrl
    case "{{EXECUTOR_ID}}" => execId.toString
    case "{{HOSTNAME}}" => host
    case "{{CORES}}" => cores.toString
    case "{{APP_ID}}" => appId
    case other => other
  }

  /**
   * Download and run the executor described in our ApplicationDescription
   * workerThread执行主要是调用fetchAndRunExecutor
   */
  private def fetchAndRunExecutor() {
    try {
      // Launch the process
      //在ExecutorRunner启动中会创建进程生成器ProcessBuilder
      //然后由该生成器创建CoarseGrainedExecutorBackend对象，该对象是Executor运行的容器，
      //最后worker发送ExecutorStateChanged消息给master.

      //通过应用程序的信息和环境配置创建构造器builder
      //创建 进程builder
      val builder = CommandUtils.buildProcessBuilder(appDesc.command, new SecurityManager(conf),
        memory, sparkHome.getAbsolutePath, substituteVariables)
      val command = builder.command()
      val formattedCommand = command.asScala.mkString("\"", "\" \"", "\"")
      logInfo(s"Launch command: $formattedCommand")

      //在构造器builder中添加执行目录等信息
      builder.directory(executorDir)
      //为进程builder设置环境变量
      builder.environment.put("SPARK_EXECUTOR_DIRS", appLocalDirs.mkString(File.pathSeparator))
      // In case we are running this from within the Spark Shell, avoid creating a "scala"
      // parent process for the executor command
      builder.environment.put("SPARK_LAUNCH_WITH_SCALA", "0")

      //在构造器builder中添加监控页面输入日志地址信息
      // Add webUI log urls
      val baseUrl =
        s"http://$publicAddress:$webUiPort/logPage/?appId=$appId&executorId=$execId&logType="
      builder.environment.put("SPARK_LOG_URL_STDERR", s"${baseUrl}stderr")
      builder.environment.put("SPARK_LOG_URL_STDOUT", s"${baseUrl}stdout")

      //启动构造器，创建CoarseGrainedExecutorBackend实例。
      //builder start的是CoarseGrainedExecutorBackend实例进程
      process = builder.start()
      val header = "Spark Executor Command: %s\n%s\n\n".format(
        formattedCommand, "=" * 40)

      // Redirect its stdout and stderr to files
      // 重定向它的stdout和stderr到文件中
      val stdout = new File(executorDir, "stdout")
      stdoutAppender = FileAppender(process.getInputStream, stdout, conf)

      val stderr = new File(executorDir, "stderr")
      Files.write(header, stderr, UTF_8)
      stderrAppender = FileAppender(process.getErrorStream, stderr, conf)

      // Wait for it to exit; executor may exit with code 0 (when driver instructs it to shutdown)
      // or with nonzero exit code
      // 等待进程退出。
      // 当driver通知该进程退出
      // executor会退出并返回0或者非0的exitCode
      //等待CoarseGrainedExecutorBackend运行结束，当结束时向worker发送退出状态信息。
      val exitCode = process.waitFor()
      state = ExecutorState.EXITED
      val message = "Command exited with code " + exitCode
      // 给Worker发送ExecutorStateChanged信号
      worker.send(ExecutorStateChanged(appId, execId, state, Some(message), Some(exitCode)))
    } catch {
      case interrupted: InterruptedException => {
        logInfo("Runner thread for executor " + fullId + " interrupted")
        state = ExecutorState.KILLED
        killProcess(None)
      }
      case e: Exception => {
        logError("Error running executor", e)
        state = ExecutorState.FAILED
        killProcess(Some(e.toString))
      }
    }
  }
}
