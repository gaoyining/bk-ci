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

package com.tencent.devops.process.engine.dao

import com.tencent.devops.model.process.Tables.T_PIPELINE_BUILD_VAR
import com.tencent.devops.model.process.tables.records.TPipelineBuildVarRecord
import org.jooq.DSLContext
import org.jooq.InsertOnDuplicateSetMoreStep
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Repository

@Repository
class PipelineBuildVarDao @Autowired constructor() {

    fun save(
        dslContext: DSLContext,
        projectId: String,
        pipelineId: String,
        buildId: String,
        name: String,
        value: Any
    ) {

        val count =
            with(T_PIPELINE_BUILD_VAR) {
                dslContext.insertInto(
                    this,
                    PROJECT_ID,
                    PIPELINE_ID,
                    BUILD_ID,
                    KEY,
                    VALUE
                )
                    .values(projectId, pipelineId, buildId, name, value.toString())
                    .onDuplicateKeyUpdate()
                    .set(PROJECT_ID, projectId)
                    .set(PIPELINE_ID, pipelineId)
                    .set(VALUE, value.toString())
                    .execute()
            }

        logger.info("save the buildVariable=$name $value, result=$count")
    }

    fun getVars(dslContext: DSLContext, buildId: String, key: String? = null): MutableMap<String, String> {

        with(T_PIPELINE_BUILD_VAR) {
            val where = dslContext.selectFrom(this)
                .where(BUILD_ID.eq(buildId))
            if (key != null) {
                where.and(KEY.eq(key))
            }
            val result = where.fetch()
            val map = mutableMapOf<String, String>()
            result?.forEach {
                map[it[KEY]] = it[VALUE]
            }
            return map
        }
    }

    @Suppress("unused")
    fun deleteBuildVar(dslContext: DSLContext, buildId: String, varName: String? = null): Int {
        return with(T_PIPELINE_BUILD_VAR) {
            val delete = dslContext.delete(this).where(BUILD_ID.eq(buildId))
            if (!varName.isNullOrBlank()) {
                delete.and(KEY.eq(varName))
            }
            delete.execute()
        }
    }

    fun batchSave(
        dslContext: DSLContext,
        projectId: String,
        pipelineId: String,
        buildId: String,
        variables: Map<String, Any>
    ) {
        val sets =
            mutableListOf<InsertOnDuplicateSetMoreStep<TPipelineBuildVarRecord>>()
        with(T_PIPELINE_BUILD_VAR) {
            val maxLength = VALUE.dataType.length()
            variables.forEach { (key, value) ->
                val valueString = value.toString()
                if (valueString.length > maxLength) {
                    logger.warn("[$buildId]|[$pipelineId]|ABANDON_DATA|len[$key]=${valueString.length}(max=$maxLength)")
                    return@forEach
                }
                val set = dslContext.insertInto(this)
                    .set(PROJECT_ID, projectId)
                    .set(PIPELINE_ID, pipelineId)
                    .set(BUILD_ID, buildId)
                    .set(KEY, key)
                    .set(VALUE, valueString)
                    .onDuplicateKeyUpdate()
                    .set(VALUE, valueString)
                sets.add(set)
            }
        }
        if (sets.isNotEmpty()) {
            val count = dslContext.batch(sets).execute()
            var success = 0
            count.forEach {
                if (it == 1) {
                    success++
                }
            }
            logger.info("[$buildId]|batchSave_vars|total=${count.size}|success_count=$success")
        }
    }

    fun deletePipelineBuildVar(dslContext: DSLContext, projectId: String, pipelineId: String) {
        return with(T_PIPELINE_BUILD_VAR) {
            dslContext.delete(this).where(PROJECT_ID.eq(projectId))
                .and(PIPELINE_ID.eq(pipelineId)).execute()
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PipelineBuildVarDao::class.java)
    }
}
