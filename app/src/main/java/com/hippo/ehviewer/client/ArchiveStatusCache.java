package com.hippo.ehviewer.client;

import java.util.HashSet;
import java.util.Set;

/**
 * 内存缓存：已归档到 NAS 的 gid 集合
 * 生命周期：随 GalleryListScene 销毁而清除
 */
public class ArchiveStatusCache {
    private final Set<Long> archivedGids = new HashSet<>();

    /**
     * 添加已归档的 gid 集合
     */
    public void addAll(Set<Long> gids) {
        if (gids != null) {
            archivedGids.addAll(gids);
        }
    }

    /**
     * 检查 gid 是否已归档
     */
    public boolean contains(long gid) {
        return archivedGids.contains(gid);
    }

    /**
     * 清空缓存（刷新列表时调用）
     */
    public void clear() {
        archivedGids.clear();
    }
}