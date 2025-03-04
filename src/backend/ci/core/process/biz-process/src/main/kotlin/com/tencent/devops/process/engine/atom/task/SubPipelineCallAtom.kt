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

package com.tencent.devops.process.engine.atom.task

import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.common.pipeline.enums.ChannelCode
import com.tencent.devops.process.pojo.ErrorType
import com.tencent.devops.common.pipeline.enums.StartType
import com.tencent.devops.common.pipeline.pojo.element.SubPipelineCallElement
import com.tencent.devops.common.pipeline.pojo.element.atom.SubPipelineType
import com.tencent.devops.common.service.utils.HomeHostUtil
import com.tencent.devops.log.utils.LogUtils
import com.tencent.devops.process.constant.ProcessMessageCode.ERROR_BUILD_TASK_SUBPIPELINEID_NOT_EXISTS
import com.tencent.devops.process.constant.ProcessMessageCode.ERROR_BUILD_TASK_SUBPIPELINEID_NULL
import com.tencent.devops.process.engine.atom.AtomResponse
import com.tencent.devops.process.engine.atom.IAtomTask
import com.tencent.devops.process.pojo.AtomErrorCode
import com.tencent.devops.process.engine.exception.BuildTaskException
import com.tencent.devops.process.engine.pojo.PipelineBuildTask
import com.tencent.devops.process.engine.service.PipelineBuildService
import com.tencent.devops.process.engine.service.PipelineRepositoryService
import com.tencent.devops.process.engine.service.PipelineRuntimeService
import com.tencent.devops.process.engine.service.PipelineService
import com.tencent.devops.process.utils.PIPELINE_START_CHANNEL
import com.tencent.devops.process.utils.PIPELINE_START_PIPELINE_USER_ID
import com.tencent.devops.process.utils.PIPELINE_START_TYPE
import com.tencent.devops.process.utils.PIPELINE_START_USER_ID
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
class SubPipelineCallAtom @Autowired constructor(
    private val rabbitTemplate: RabbitTemplate,
    private val pipelineRuntimeService: PipelineRuntimeService,
    private val pipelineBuildService: PipelineBuildService,
    private val pipelineRepositoryService: PipelineRepositoryService,
    private val pipelineService: PipelineService
) : IAtomTask<SubPipelineCallElement> {

    override fun getParamElement(task: PipelineBuildTask): SubPipelineCallElement {
        return JsonUtil.mapTo(task.taskParams, SubPipelineCallElement::class.java)
    }

    override fun tryFinish(task: PipelineBuildTask, param: SubPipelineCallElement, runVariables: Map<String, String>, force: Boolean): AtomResponse {
        logger.info("[${task.buildId}]|ATOM_SUB_PIPELINE_FINISH|status=${task.status}")
        if (BuildStatus.isFinish(task.status)) {
            return AtomResponse(task.status)
        }

        return if (task.subBuildId == null || task.subBuildId.isNullOrBlank()) {
            AtomResponse(
                buildStatus = BuildStatus.FAILED,
                errorType = ErrorType.USER,
                errorCode = AtomErrorCode.USER_RESOURCE_NOT_FOUND,
                errorMsg = "找不到对应子流水线"
            )
        } else {
            val buildInfo = pipelineRuntimeService.getBuildInfo(task.subBuildId!!)
            return if (buildInfo == null) {
                LogUtils.addRedLine(rabbitTemplate, task.buildId, "找不到对应子流水线", task.taskId, task.containerHashId, task.executeCount ?: 1)
                AtomResponse(
                    buildStatus = BuildStatus.FAILED,
                    errorType = ErrorType.USER,
                    errorCode = AtomErrorCode.USER_RESOURCE_NOT_FOUND,
                    errorMsg = "找不到对应子流水线"
                )
            } else {
                when {
                    BuildStatus.isCancel(buildInfo.status) ->
                        LogUtils.addYellowLine(rabbitTemplate, task.buildId, "子流水线被取消", task.taskId, task.containerHashId, task.executeCount ?: 1)
                    BuildStatus.isFailure(buildInfo.status) ->
                        LogUtils.addYellowLine(rabbitTemplate, task.buildId, "子流水线执行失败", task.taskId, task.containerHashId, task.executeCount ?: 1)
                    BuildStatus.isSuccess(buildInfo.status) ->
                        LogUtils.addLine(rabbitTemplate, task.buildId, "子流水线执行成功", task.taskId, task.containerHashId, task.executeCount ?: 1)
                    else ->
                        return AtomResponse(
                            buildStatus = task.status,
                            errorType = task.errorType,
                            errorCode = task.errorCode,
                            errorMsg = task.errorMsg
                        )
                }
                AtomResponse(
                    buildStatus = buildInfo.status,
                    errorType = buildInfo.errorType,
                    errorCode = buildInfo.errorCode,
                    errorMsg = buildInfo.errorMsg
                )
            }
        }
    }

    // TODO Exception中的错误码对应提示信息修改提取
    override fun execute(task: PipelineBuildTask, param: SubPipelineCallElement, runVariables: Map<String, String>): AtomResponse {
        logger.info("Enter SubPipelineCallAtom run...")

        val projectId = task.projectId
        val pipelineId = task.pipelineId
        val buildId = task.buildId
        val taskId = task.taskId
        val subPipelineId = if (param.subPipelineType == SubPipelineType.NAME && !param.subPipelineName.isNullOrBlank()) {
            val subPipelineRealName = parseVariable(param.subPipelineName, runVariables)
            pipelineService.getPipelineIdByNames(projectId, setOf(subPipelineRealName), true)[subPipelineRealName]
        } else {
            parseVariable(param.subPipelineId, runVariables)
        }

        if (subPipelineId.isNullOrBlank())
            throw BuildTaskException(
                errorType = ErrorType.USER,
                errorCode = ERROR_BUILD_TASK_SUBPIPELINEID_NULL,
                errorMsg = "子流水线ID参数为空，请检查流水线重新保存后并重新执行",
                pipelineId = task.pipelineId,
                buildId = buildId,
                taskId = taskId
            )

        // 注意：加projectId限定了不允许跨项目，防止恶意传递了跨项目的项目id
        val pipelineInfo = (pipelineRepositoryService.getPipelineInfo(projectId, subPipelineId!!)
            ?: throw BuildTaskException(
                errorType = ErrorType.USER,
                errorCode = ERROR_BUILD_TASK_SUBPIPELINEID_NOT_EXISTS,
                errorMsg = "子流水线[$subPipelineId]不存在,请检查流水线是否还存在",
                pipelineId = task.pipelineId,
                buildId = buildId,
                taskId = taskId
            )
        )

        val channelCode = ChannelCode.valueOf(runVariables[PIPELINE_START_CHANNEL]!!)

        val startType = runVariables[PIPELINE_START_TYPE]!!

        val userId = if (startType == StartType.PIPELINE.name) {
            runVariables[PIPELINE_START_PIPELINE_USER_ID]!!
        } else {
            runVariables[PIPELINE_START_USER_ID]!!
        }

        logger.info("Get the variables - ($runVariables)")

        logger.info("Start call sub pipeline by user $userId of build $buildId")

        val startParams = mutableMapOf<String, Any>()
        if (param.parameters != null) {
            param.parameters!!.forEach {
                startParams[it.key] = parseVariable(it.value, runVariables)
            }
        }
        val subBuildId = pipelineBuildService.subpipelineStartup(
            userId, StartType.PIPELINE, projectId, pipelineId, buildId, taskId,
            subPipelineId, channelCode, startParams, false, false)
        LogUtils.addLine(rabbitTemplate, buildId, "已启动子流水线 - ${pipelineInfo.pipelineName}", taskId, task.containerHashId, task.executeCount ?: 1)
        LogUtils.addLine(rabbitTemplate,
            buildId,
            "<a target='_blank' href='${HomeHostUtil.innerServerHost()}/console/pipeline/$projectId/$subPipelineId/detail/$subBuildId'>查看子流水线执行详情</a>",
            taskId,
            task.containerHashId,
            task.executeCount ?: 1)

        return AtomResponse(if (param.asynchronous) BuildStatus.SUCCEED else BuildStatus.CALL_WAITING)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SubPipelineCallAtom::class.java)
    }
}
