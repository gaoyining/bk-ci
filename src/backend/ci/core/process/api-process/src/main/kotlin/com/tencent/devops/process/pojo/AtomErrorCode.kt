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

package com.tencent.devops.process.pojo

/**
 * 错误码制定规则（0代表成功，为了兼容历史接口的成功状态都是返回0）：
 * 1、错误码总长度为8位，
 * 2、前2位数字代表系统名称（如16代表蓝盾平台）
 * 3、第3位和第4位数字代表微服务模块（00：common-公共模块 01：process-流水线 02：artifactory-版本仓库 03:dispatch-分发 04：dockerhost-docker机器
 *    05:environment-蓝盾环境 06：experience-版本体验 07：image-镜像 08：log-蓝盾日志 09：measure-度量 10：monitoring-监控 11：notify-通知
 *    12：openapi-开放api接口 13：plugin-插件 14：quality-质量红线 15：repository-代码库 16：scm-软件配置管理 17：support-蓝盾支撑服务
 *    18：ticket-证书凭据 19：project-项目管理 20：store-商店）
 * 4、最后4位数字代表具体插件模块下不同错误的唯一标识
 * 5、第5-6位在process插件中用于区分错误类型，50为系统错误，51位插件错误
 *
 * @since: 2019-09-26
 * @version: $Revision$ $Date$ $LastChangedBy$
 *
 */
object AtomErrorCode {
    // 蓝盾系统错误
    const val SUCESSS = "0" // 成功
    const val SYSTEM_DAEMON_INTERRUPTED = 16015000 // 守护进程中断
    const val SYSTEM_SERVICE_ERROR = 16015001 // 系统内部服务调用出错
    const val SYSTEM_OUTTIME_ERROR = 16015002 // 执行请求超时
    const val SYSTEM_WORKER_LOADING_ERROR = 16015003 // worker插件加载出错
    const val SYSTEM_WORKER_INITIALIZATION_ERROR = 16015004 // 构建机拉起出错
    const val SYSTEM_INNER_TASK_ERROR = 16015005 // 系统任务执行出错

    // 插件执行错误
    const val USER_DEFAULT_ERROR = 16015100 // 默认错误
    const val USER_INPUT_INVAILD = 16015101 // 用户输入数据有误
    const val USER_TASK_OPERATE_FAIL = 16015102 // 插件执行过程出错
    const val USER_RESOURCE_NOT_FOUND = 16015103 // 找不到对应系统资源
    const val USER_JOB_OUTTIME_LIMIT = 16015104 // 用户Job排队超时（自行限制）
}