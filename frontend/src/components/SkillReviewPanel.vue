<template>
  <div class="skill-review-panel">
    <div class="panel-header">
      <strong>自学习 Skill 审核</strong>
      <el-button size="small" :loading="reviews.loading" @click="reviews.loadReviews()">刷新</el-button>
    </div>
    <p class="panel-desc">Agent 创建的草稿必须人工审核后才能晋升为正式 Skill。</p>

    <el-alert v-if="reviews.error" :title="reviews.error" type="error" show-icon :closable="false" style="margin-bottom: 8px" />

    <div v-if="reviews.reviews.length === 0 && !reviews.loading" class="empty-state">
      暂无待审核的 Skill 草稿
    </div>

    <el-table v-else :data="reviews.reviews" size="small" style="width: 100%">
      <el-table-column prop="skillName" label="Skill 名称" min-width="120" />
      <el-table-column prop="description" label="描述" min-width="160" show-overflow-tooltip />
      <el-table-column prop="status" label="状态" width="90">
        <template #default="{ row }">
          <el-tag
            :type="row.status === 'APPROVED' ? 'success' : row.status === 'REJECTED' ? 'danger' : 'warning'"
            size="small">
            {{ row.status === 'APPROVED' ? '已批准' : row.status === 'REJECTED' ? '已拒绝' : '待审核' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="useCount" label="调用次数" width="80" />
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <template v-if="row.status === 'PENDING'">
            <el-button size="small" type="primary" @click="handleApprove(row.skillName)">批准</el-button>
            <el-button size="small" type="danger" @click="handleReject(row.skillName)">拒绝</el-button>
          </template>
          <span v-else class="reviewed-label">已处理</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useSkillReviewsStore } from '../stores/skillReviews'

const reviews = useSkillReviewsStore()

onMounted(() => {
  reviews.loadReviews()
})

async function handleApprove(skillName: string) {
  try {
    await reviews.approve(skillName, ['prod'])
    ElMessage.success(`已批准 ${skillName}`)
  } catch (e) {
    ElMessage.error(`批准失败: ${e instanceof Error ? e.message : String(e)}`)
  }
}

async function handleReject(skillName: string) {
  try {
    await reviews.reject(skillName, 'Rejected from Web review')
    ElMessage.warning(`已拒绝 ${skillName}`)
  } catch (e) {
    ElMessage.error(`拒绝失败: ${e instanceof Error ? e.message : String(e)}`)
  }
}
</script>

<style scoped>
.skill-review-panel {
  padding: 8px;
}
.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}
.panel-desc {
  font-size: 12px;
  color: #909399;
  margin: 0 0 10px;
}
.empty-state {
  color: #909399;
  font-size: 13px;
  text-align: center;
  padding: 20px 0;
}
.reviewed-label {
  font-size: 12px;
  color: #909399;
}
</style>
