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

package com.tencent.devops.process.engine.dao.template

import com.fasterxml.jackson.databind.ObjectMapper
import com.tencent.devops.common.api.model.SQLPage
import com.tencent.devops.model.process.tables.TPipelineInfo
import com.tencent.devops.model.process.tables.TPipelineSetting
import com.tencent.devops.model.process.tables.TTemplatePipeline
import com.tencent.devops.model.process.tables.records.TTemplatePipelineRecord
import com.tencent.devops.process.pojo.template.TemplateInstanceUpdate
import org.jooq.DSLContext
import org.jooq.Record1
import org.jooq.Result
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/**
 * deng
 * 2019-01-07
 */
@Repository
class TemplatePipelineDao @Autowired constructor(private val objectMapper: ObjectMapper) {

    fun create(
        dslContext: DSLContext,
        pipelineId: String,
        templateVersion: Long,
        versionName: String,
        templateId: String,
        userId: String,
        buildNo: String?,
        param: String?
    ) {
        with(TTemplatePipeline.T_TEMPLATE_PIPELINE) {
            val now = LocalDateTime.now()
            dslContext.insertInto(
                this,
                PIPELINE_ID,
                VERSION,
                VERSION_NAME,
                TEMPLATE_ID,
                CREATOR,
                UPDATOR,
                CREATED_TIME,
                UPDATED_TIME,
                BUILD_NO,
                PARAM
            )
                .values(
                    pipelineId,
                    templateVersion,
                    versionName,
                    templateId,
                    userId,
                    userId,
                    now,
                    now,
                    buildNo ?: "",
                    param ?: ""
                )
                .execute()
        }
    }

    fun get(dslContext: DSLContext, pipelineId: String): TTemplatePipelineRecord? {
        with(TTemplatePipeline.T_TEMPLATE_PIPELINE) {
            return dslContext.selectFrom(this)
                .where(PIPELINE_ID.eq(pipelineId))
                .fetchOne()
        }
    }

    fun listByPipelines(
        dslContext: DSLContext,
        pipelineIds: Set<String>
    ): Result<TTemplatePipelineRecord> {
        with(TTemplatePipeline.T_TEMPLATE_PIPELINE) {
            return dslContext.selectFrom(this)
                .where(PIPELINE_ID.`in`(pipelineIds))
                .fetch()
        }
    }

    fun listPipeline(
        dslContext: DSLContext,
        templateIds: Collection<String>
    ): Result<TTemplatePipelineRecord> {
        with(TTemplatePipeline.T_TEMPLATE_PIPELINE) {
            return dslContext.selectFrom(this)
                .where(TEMPLATE_ID.`in`(templateIds))
                .fetch()
        }
    }

    fun listPipelineInPage(
        dslContext: DSLContext,
        templateId: String,
        page: Int? = null,
        pageSize: Int? = null,
        searchKey: String? = null
    ): SQLPage<TTemplatePipelineRecord> {
        if (searchKey != null) {
            var nameLikedPipelineIds: Set<String> = mutableSetOf()
            with(TPipelineSetting.T_PIPELINE_SETTING) {
                val nameLikedPipeline = dslContext.selectFrom(this)
                    .where(NAME.like("%$searchKey%"))
                    .fetch()
                nameLikedPipelineIds = nameLikedPipeline.map { it.pipelineId }.toSet()
            }
            with(TTemplatePipeline.T_TEMPLATE_PIPELINE) {
                val baseStep = dslContext.selectFrom(this)
                    .where(TEMPLATE_ID.eq(templateId), PIPELINE_ID.`in`(nameLikedPipelineIds))
                val allCount = baseStep.count()
                val records = if (null != page && null != pageSize) {
                    baseStep.limit((page - 1) * pageSize, pageSize).fetch()
                } else {
                    baseStep.fetch()
                }
                return SQLPage(allCount.toLong(), records)
            }
        }

        with(TTemplatePipeline.T_TEMPLATE_PIPELINE) {
            val baseStep = dslContext.selectFrom(this)
                .where(TEMPLATE_ID.eq(templateId))
            val allCount = baseStep.count()
            val records = if (null != page && null != pageSize) {
                baseStep.limit((page - 1) * pageSize, pageSize).fetch()
            } else {
                baseStep.fetch()
            }
            return SQLPage(allCount.toLong(), records)
        }
    }

    fun listPipeline(
        dslContext: DSLContext,
        templateId: String,
        version: Long
    ): Result<TTemplatePipelineRecord> {
        with(TTemplatePipeline.T_TEMPLATE_PIPELINE) {
            return dslContext.selectFrom(this)
                .where(TEMPLATE_ID.eq(templateId))
                .and(VERSION.eq(version))
                .fetch()
        }
    }

    fun delete(
        dslContext: DSLContext,
        pipelineId: String
    ) {
        with(TTemplatePipeline.T_TEMPLATE_PIPELINE) {
            dslContext.deleteFrom(this)
                .where(PIPELINE_ID.eq(pipelineId))
                .execute()
        }
    }

    fun update(
        dslContext: DSLContext,
        templateVersion: Long,
        versionName: String,
        userId: String,
        instance: TemplateInstanceUpdate
    ): Int {
        with(TTemplatePipeline.T_TEMPLATE_PIPELINE) {
            return dslContext.update(this)
                .set(VERSION, templateVersion)
                .set(VERSION_NAME, versionName)
                .set(UPDATOR, userId)
                .set(
                    BUILD_NO,
                    if (instance.buildNo == null) null else objectMapper.writeValueAsString(instance.buildNo)
                )
                .set(PARAM, objectMapper.writeValueAsString(instance.param))
                .set(UPDATED_TIME, LocalDateTime.now())
                .where(PIPELINE_ID.eq(instance.pipelineId))
                .execute()
        }
    }

    fun listPipelineTemplate(
        dslContext: DSLContext,
        pipelineIds: Collection<String>
    ): Result<TTemplatePipelineRecord>? {
        return with(TTemplatePipeline.T_TEMPLATE_PIPELINE) {
            dslContext.selectFrom(this)
                .where(PIPELINE_ID.`in`(pipelineIds))
                .fetch()
        }
    }

    fun countPipelineInstancedByTemplate(
        dslContext: DSLContext,
        projectIds: Set<String>
    ): Record1<Int> {
        // 流水线有软删除，需要过滤
        val t1 = TTemplatePipeline.T_TEMPLATE_PIPELINE.`as`("t1")
        val t2 = TPipelineInfo.T_PIPELINE_INFO.`as`("t2")
        return dslContext.selectCount().from(t1).join(t2).on(t1.PIPELINE_ID.eq(t2.PIPELINE_ID))
            .where(t2.DELETE.eq(false))
            .and(t2.PROJECT_ID.`in`(projectIds))
            .fetchOne()
    }

    fun countTemplateInstanced(
        dslContext: DSLContext,
        projectIds: Set<String>
    ): Record1<Int> {
        // 模板没有软删除，直接查即可
        val t1 = TTemplatePipeline.T_TEMPLATE_PIPELINE.`as`("t1")
        val t2 = TPipelineInfo.T_PIPELINE_INFO.`as`("t2")
        return dslContext.select(
            t1.TEMPLATE_ID.countDistinct()
        ).from(t1).join(t2).on(t1.PIPELINE_ID.eq(t2.PIPELINE_ID))
            .where(t2.PROJECT_ID.`in`(projectIds))
            .fetchOne()
    }

    /**
     * 查询实例化的原始模板总数
     */
    fun countSrcTemplateInstanced(
        dslContext: DSLContext,
        srcTemplateIds: Set<String>
    ): Record1<Int> {
        with(TTemplatePipeline.T_TEMPLATE_PIPELINE) {
            return dslContext.select(TEMPLATE_ID.countDistinct()).from(this)
                .where(TEMPLATE_ID.`in`(srcTemplateIds)).fetchOne()
        }
    }
}