package com.fancy.hotel.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 酒店知识库的读取（P5.3）
 *
 * <p>只做两件事：把原文捞出来灌进 ES、把酒店名捞出来做名称匹配。
 *
 * <p>为什么用 {@code Map} 而不是建实体类：这两条查询只服务于"建索引"，
 * 走一次就完事，没有复用价值。为它写 entity + VO 是两个多余的文件。
 * 注意 SQL 里用 {@code AS} 显式起别名 —— MyBatis 不会对 Map 结果做下划线转驼峰。
 */
@Mapper
public interface HotelKbMapper {

    /** 知识库原文（真相来源，ES 只是它的索引） */
    @Select("SELECT hotel_id AS hotelId, section, content, doc_type AS docType "
            + "FROM hotel_kb_doc WHERE is_deleted = 0 ORDER BY hotel_id, id")
    List<Map<String, Object>> selectAllDocs();

    /** 在售酒店的名称 */
    @Select("SELECT hotel_id AS hotelId, name AS hotelName "
            + "FROM hotel WHERE status = 1 AND is_deleted = 0")
    List<Map<String, Object>> selectHotelNames();

    /** 可选：按酒店过滤原文 */
    @Select("SELECT hotel_id AS hotelId, section, content, doc_type AS docType "
            + "FROM hotel_kb_doc WHERE is_deleted = 0 AND hotel_id = #{hotelId} ORDER BY id")
    List<Map<String, Object>> selectDocsByHotel(@Param("hotelId") Long hotelId);
}
