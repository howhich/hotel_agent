package com.fancy.hotel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fancy.hotel.domain.entity.IdempotentRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 幂等记录
 */
public interface IdempotentRecordMapper extends BaseMapper<IdempotentRecord> {

    @Select("SELECT * FROM idempotent_record WHERE idem_key = #{idemKey}")
    IdempotentRecord selectByKey(@Param("idemKey") String idemKey);

    /**
     * 标记完成并记录结果引用
     *
     * <p>带 {@code status = 0} 前置条件，避免覆盖别人已经写好的结果。
     */
    @Update("UPDATE idempotent_record SET status = 1, result_ref = #{resultRef}, update_time = NOW() "
            + "WHERE idem_key = #{idemKey} AND status = 0")
    int markDone(@Param("idemKey") String idemKey, @Param("resultRef") String resultRef);

    /**
     * 删除记录（执行失败时用，让同一个请求可以重试）
     *
     * <p>为什么失败要删而不是标记 status=2：本项目的业务方法都是 {code @Transactional}，
     * 失败就等于没有任何副作用，保留一条"失败记录"只会让用户的重试也被拒。
     */
    @Delete("DELETE FROM idempotent_record WHERE idem_key = #{idemKey} AND status = 0")
    int deleteIfRunning(@Param("idemKey") String idemKey);
}
