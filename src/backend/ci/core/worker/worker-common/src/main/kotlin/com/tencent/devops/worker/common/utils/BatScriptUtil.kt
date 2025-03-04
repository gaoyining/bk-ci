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

package com.tencent.devops.worker.common.utils

import com.tencent.devops.common.pipeline.enums.BuildScriptType
import com.tencent.devops.worker.common.CommonEnv
import com.tencent.devops.worker.common.WORKSPACE_ENV
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.Charset

object BatScriptUtil {
    private const val setEnv = ":setEnv\r\n" +
        "    set file_save_dir=\"##resultFile##\"\r\n" +
        "    echo %~1=%~2 >>%file_save_dir%\r\n" +
        "    set %~1=%~2\r\n" +
        "    goto:eof\r\n"

    private const val setGateValue = ":setGateValue\r\n" +
        "    set file_save_dir=\"##gateValueFile##\"\r\n" +
        "    echo %~1=%~2 >>%file_save_dir%\r\n" +
        "    set %~1=%~2\r\n" +
        "    goto:eof\r\n"
    private const val GATEWAY_FILE = "gatewayValueFile.ini"

    private val logger = LoggerFactory.getLogger(BatScriptUtil::class.java)

    fun execute(
        buildId: String,
        script: String,
        runtimeVariables: Map<String, String>,
        dir: File,
        outerCommandFunc:
        ((scriptType: BuildScriptType, buildId: String, file: File, workspace: File) -> String)? = null,
        prefix: String = ""
    ): String {
        try {
            val tmpDir = System.getProperty("java.io.tmpdir")
            val file = if (tmpDir.isNullOrBlank()) {
                File.createTempFile("paas_build_script_", ".bat")
            } else {
                File(tmpDir).mkdirs()
                File.createTempFile("paas_build_script_", ".bat", File(tmpDir))
            }
            file.deleteOnExit()

            val command = StringBuilder()

            command.append("@echo off")
                .append("\r\n")
                .append("set $WORKSPACE_ENV=${dir.absolutePath}\r\n")
                .append("set DEVOPS_BUILD_SCRIPT_FILE=${file.absolutePath}\r\n")
                .append("\r\n")

            // FIXME: 需要处理 |和= 号可能造成的问题
            runtimeVariables.plus(CommonEnv.getCommonEnv())
                .forEach { (name, value) ->
                    val clean = value.replace("\"", "\\\"") // 转义
                    command.append("set $name=\"$clean\"\r\n") // 双引号防止变量值有空格而意外截断定义
                    command.append("set $name=%$name:~1,-1%\r\n") // 去除双引号，防止被程序读到有双引号的变量值
                }

            command.append(script.replace("\n", "\r\n"))
                .append("\r\n")
                .append("exit")
                .append("\r\n")
                .append(setEnv.replace("##resultFile##", File(dir, "result.log").absolutePath))
                .append(setGateValue.replace("##gateValueFile##", File(dir, GATEWAY_FILE).canonicalPath))

            val charset = Charset.defaultCharset()
            logger.info("The default charset is $charset")

            file.writeText(command.toString(), charset)
            return if (outerCommandFunc == null) {
                logger.info("start to run windows script")
                CommandLineUtils.execute("cmd.exe /C \"${file.canonicalPath}\"", dir, true, prefix)
            } else {
                logger.info("start to run windows codecc script")
                outerCommandFunc(BuildScriptType.BAT, buildId, file, dir)
            }
        } catch (e: Throwable) {
            logger.warn("Fail to execute bat script $script", e)
            throw e
        }
    }
}
