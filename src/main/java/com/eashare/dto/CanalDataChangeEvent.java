package com.eashare.dto;

import lombok.Data;
import java.io.Serializable;
import java.util.Map;

/**
 * Canal 数据变更事件 DTO
 */
@Data
public class CanalDataChangeEvent implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 数据库名称
     */
    private String database;

    /**
     * 表名称
     */
    private String table;

    /**
     * 事件类型: INSERT, UPDATE, DELETE
     */
    private String type;

    /**
     * 变更前数据 (DELETE 和 UPDATE 时有值)
     */
    private Map<String, String> oldData;

    /**
     * 变更后数据 (INSERT 和 UPDATE 时有值)
     */
    private Map<String, String> newData;

    /**
     * 主键ID
     */
    private String pkId;
}
