package com.github.h3yow.datasetschemaregistryjava.store;

import com.github.h3yow.datasetschemaregistryjava.model.SchemaDefinition;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaVersion;

import java.util.List;
import java.util.Optional;

/** 本地持久化存储抽象。 */
public interface SchemaStore {

    /** 原子化追加一个新版本，返回持久化后的版本记录。 */
    SchemaVersion appendVersion(String dataset, int version, SchemaDefinition schema,
                                Integer parentVersion, String contentHash);

    /** 读取数据集全部版本（按版本号升序）。 */
    List<SchemaVersion> listVersions(String dataset);

    /** 读取指定版本。 */
    Optional<SchemaVersion> findVersion(String dataset, int version);

    /** 查询已存在的数据集名称。 */
    boolean datasetExists(String dataset);

    /** 原子化把 head 指针移动到目标版本；目标不存在时返回 false。 */
    boolean moveHead(String dataset, int targetVersion);

    /** 读取当前 head（生效）版本号；数据集不存在时返回空。 */
    Optional<Integer> readHead(String dataset);
}
