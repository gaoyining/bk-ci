/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.process.engine.atom

import com.tencent.devops.common.api.util.timestampmilli
import com.tencent.devops.common.event.dispatcher.pipeline.PipelineEventDispatcher
import com.tencent.devops.common.event.pojo.pipeline.PipelineBuildElementFinishBroadCastEvent
import com.tencent.devops.common.log.Ansi
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.common.pipeline.utils.SkipElementUtils
import com.tencent.devops.common.service.utils.SpringContextUtil
import com.tencent.devops.log.utils.LogUtils
import com.tencent.devops.process.engine.exception.BuildTaskException
import com.tencent.devops.process.engine.pojo.PipelineBuildTask
import com.tencent.devops.process.engine.service.PipelineBuildDetailService
import com.tencent.devops.process.engine.service.PipelineRuntimeService
import com.tencent.devops.process.engine.service.measure.MeasureService
import com.tencent.devops.process.jmx.elements.JmxElements
import com.tencent.devops.process.pojo.ErrorType
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class TaskAtomService @Autowired(required = false) constructor(
    private val rabbitTemplate: RabbitTemplate,
    private val pipelineRuntimeService: PipelineRuntimeService,
    private val pipelineBuildDetailService: PipelineBuildDetailService,
    private val jmxElements: JmxElements,
    private val pipelineEventDispatcher: PipelineEventDispatcher,
    @Autowired(required = false)
    private val measureService: MeasureService?
) {

    fun start(task: PipelineBuildTask): AtomResponse {
        val startTime = System.currentTimeMillis()
        val elementType = task.taskType
        jmxElements.execute(elementType)
        var atomResponse = AtomResponse(BuildStatus.FAILED)
        val logTagName = task.taskName + "-[" + task.taskId + "]"
        try {
            // 更新状态
            pipelineRuntimeService.updateTaskStatus(task.buildId, task.taskId, task.starter, BuildStatus.RUNNING)
            pipelineBuildDetailService.taskStart(task.buildId, task.taskId)
            val executeCount = task.executeCount ?: 1
            LogUtils.addFoldStartLine(rabbitTemplate, task.buildId, logTagName, task.taskId, task.containerHashId, executeCount)
            LogUtils.addLine(
                rabbitTemplate, task.buildId, Ansi().bold()
                    .a("Start Element").reset().toString(), task.taskId, task.containerHashId, executeCount
            )
            val runVariables = pipelineRuntimeService.getAllVariable(task.buildId)

            atomResponse = if (task.isSkip(runVariables)) { // 跳过
                AtomResponse(BuildStatus.SKIP)
            } else {
                val atomTaskDaemon = AtomTaskDaemon(task, runVariables)
                atomTaskDaemon.call()
            }
        } catch (t: BuildTaskException) {
            LogUtils.addRedLine(
                rabbitTemplate, task.buildId, "当前原子执行出现异常: " +
                    "${t.message}", task.taskId, task.containerHashId, task.executeCount ?: 1
            )
            logger.warn("Fail to execute the task atom", t)
        } catch (ignored: Throwable) {
            LogUtils.addRedLine(
                rabbitTemplate, task.buildId,
                "Fail to execute the task atom: " +
                    "${ignored.message}", task.taskId, task.containerHashId, task.executeCount ?: 1
            )
            logger.warn("Fail to execute the task atom", ignored)
        } finally {
            // 存储变量
            if (atomResponse.outputVars != null && atomResponse.outputVars!!.isNotEmpty()) {
                pipelineRuntimeService.batchSetVariable(
                    projectId = task.projectId,
                    pipelineId = task.pipelineId,
                    buildId = task.buildId,
                    variables = atomResponse.outputVars!!
                )
            }
            // 循环的是还未结束，直接返回
            if (BuildStatus.isFinish(atomResponse.buildStatus)) {
                taskEnd(
                    status = atomResponse.buildStatus,
                    task = task,
                    startTime = startTime,
                    elementType = elementType,
                    logTagName = logTagName,
                    errorType = atomResponse.errorType,
                    errorCode = atomResponse.errorCode,
                    errorMsg = atomResponse.errorMsg
                )
            }
            return atomResponse
        }
    }

    private fun taskEnd(
        status: BuildStatus,
        task: PipelineBuildTask,
        startTime: Long,
        elementType: String,
        logTagName: String,
        errorType: ErrorType?,
        errorCode: Int?,
        errorMsg: String?
    ) {
        try {
            // 更新状态
            pipelineRuntimeService.updateTaskStatus(
                buildId = task.buildId,
                taskId = task.taskId,
                userId = task.starter,
                buildStatus = status,
                errorType = errorType,
                errorCode = errorCode,
                errorMsg = errorMsg
            )
            pipelineBuildDetailService.pipelineTaskEnd(
                buildId = task.buildId,
                elementId = task.taskId,
                buildStatus = status,
                errorType = errorType,
                errorCode = errorCode,
                errorMsg = errorMsg
            )
            measureService?.postTaskData(
                projectId = task.projectId,
                pipelineId = task.pipelineId,
                taskId = task.taskId,
                atomCode = task.taskParams["atomCode"] as String? ?: "",
                name = task.taskName,
                buildId = task.buildId,
                startTime = task.startTime?.timestampmilli() ?: startTime,
                status = status,
                type = elementType,
                executeCount = task.executeCount,
                errorType = errorType?.name,
                errorCode = errorCode,
                errorMsg = errorMsg
            )
            LogUtils.addFoldEndLine(
                rabbitTemplate, task.buildId, logTagName,
                task.taskId, task.containerHashId, task.executeCount ?: 1
            )
            if (BuildStatus.isFailure(status)) {
                jmxElements.fail(elementType)
            }
        } catch (ignored: Throwable) {
            logger.error("Fail to post the task($task): ${ignored.message}")
        }
        LogUtils.stopLog(rabbitTemplate, task.buildId, task.taskId, task.containerHashId)
    }

    fun tryFinish(task: PipelineBuildTask, force: Boolean): AtomResponse {
        val startTime = System.currentTimeMillis()
        val elementType = task.taskType
        var atomResponse = AtomResponse(BuildStatus.FAILED)
        val logTagName = task.taskName + "-[" + task.taskId + "]"

        try {
            val runVariables = pipelineRuntimeService.getAllVariable(task.buildId)
            val iAtomTask = SpringContextUtil.getBean(IAtomTask::class.java, task.taskAtom)
            atomResponse = iAtomTask.tryFinish(task, runVariables, force)

            log(atomResponse, task, force)
        } catch (t: BuildTaskException) {
            LogUtils.addRedLine(
                rabbitTemplate, task.buildId, "Fail to execute the task atom: ${t.message}",
                task.taskId, task.containerHashId, task.executeCount ?: 1
            )
            logger.warn("Fail to execute the task atom", t)
        } catch (ignored: Throwable) {
            LogUtils.addRedLine(
                rabbitTemplate, task.buildId,
                "Fail to execute the task atom: ${ignored.message}",
                task.taskId, task.containerHashId, task.executeCount ?: 1
            )
            logger.warn("Fail to execute the task atom", ignored)
        } finally {
            // 存储变量
            if (atomResponse.outputVars != null && atomResponse.outputVars!!.isNotEmpty()) {
                pipelineRuntimeService.batchSetVariable(
                    projectId = task.projectId,
                    pipelineId = task.pipelineId,
                    buildId = task.buildId,
                    variables = atomResponse.outputVars!!)
            }
            // 循环的是还未结束，直接返回
            if (BuildStatus.isFinish(atomResponse.buildStatus)) {
                taskEnd(
                    status = atomResponse.buildStatus,
                    task = task,
                    startTime = startTime,
                    elementType = elementType,
                    logTagName = logTagName,
                    errorType = atomResponse.errorType,
                    errorCode = atomResponse.errorCode,
                    errorMsg = atomResponse.errorMsg
                )
                pipelineEventDispatcher.dispatch(
                    PipelineBuildElementFinishBroadCastEvent(
                        source = "build-element-${task.taskId}",
                        projectId = task.projectId,
                        pipelineId = task.pipelineId,
                        userId = "",
                        buildId = task.buildId,
                        elementId = task.taskId,
                        errorType = if (task.errorType == null) null else task.errorType!!.name,
                        errorCode = task.errorCode,
                        errorMsg = task.errorMsg
                    )
                )
            }
            return atomResponse
        }
    }

    private fun log(
        atomResponse: AtomResponse,
        task: PipelineBuildTask,
        force: Boolean
    ) {
        if (BuildStatus.isFinish(atomResponse.buildStatus)) {
            LogUtils.addLine(
                rabbitTemplate, task.buildId, "当前原子执行结束",
                task.taskId, task.containerHashId, task.executeCount ?: 1
            )
        } else {
            if (force) {
                LogUtils.addLine(
                    rabbitTemplate, task.buildId, "尝试强制终止当前原子未成功，重试中...",
                    task.taskId, task.containerHashId, task.executeCount ?: 1
                )
            }
        }
    }

    private fun PipelineBuildTask.isSkip(variables: Map<String, String>): Boolean {
        try {

            val skipValue = variables[SkipElementUtils.getSkipElementVariableName(taskId)]
            if (skipValue != null && (skipValue.toBoolean())) {
                logger.info(
                    "[$buildId]|isSkip|stage=$stageId|containerId=$containerId|taskId=$taskId| " +
                        "is skip of the build"
                )
                return true
            }
        } catch (ignored: Throwable) {
            logger.warn(
                "[$buildId]|isSkip|stage=$stageId|containerId=$containerId|taskId=$taskId| " +
                    "Fail to check if skip", ignored
            )
        }
        return false
    }

    fun runByVmTask(buildTask: PipelineBuildTask): Boolean {
        // 非官方内置的原子任务不在此运行
        if (buildTask.taskAtom.isBlank()) {
            return true
        }
        return false
    }

    companion object {
        private val logger = LoggerFactory.getLogger(TaskAtomService::class.java)
    }
}
