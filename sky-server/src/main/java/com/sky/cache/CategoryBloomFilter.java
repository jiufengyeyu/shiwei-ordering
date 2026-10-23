package com.sky.cache;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.sky.mapper.CategoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 二次开发任务1：分类 ID 布隆过滤器（防缓存穿透）
 *
 * 背景：恶意/异常请求携带不存在的 categoryId 查询菜品列表时，每次都会穿透缓存打到数据库。
 * 布隆过滤器特点：说"不存在"就一定不存在（拦截查询），说"存在"可能误判（放行后由空值缓存兜底）。
 *
 * 预热时机：应用启动时（ApplicationRunner）从 category 表加载全部分类 id；
 * 更新时机：管理端新增分类时同步 add（注意：布隆过滤器不支持删除，
 *          已删除分类的 id 残留只会导致放行后命中空值缓存，无害）。
 */
@Component
@Slf4j
public class CategoryBloomFilter implements ApplicationRunner {

    @Autowired
    private CategoryMapper categoryMapper;

    /** 预期插入 10000 个 id，误判率 1% */
    private final BloomFilter<Long> bloomFilter =
            BloomFilter.create(Funnels.longFunnel(), 10000, 0.01);

    @Override
    public void run(ApplicationArguments args) {
        List<Long> ids = categoryMapper.getAllIds();
        ids.forEach(bloomFilter::put);
        log.info("分类布隆过滤器预热完成，已加载 {} 个分类 id", ids.size());
    }

    /**
     * 判断分类 id 是否可能存在
     * @return false = 一定不存在（直接拦截）；true = 可能存在（放行，后续空值缓存兜底）
     */
    public boolean mightContain(Long categoryId) {
        if (categoryId == null) {
            // 不带分类条件的查询不走布隆过滤器
            return true;
        }
        return bloomFilter.mightContain(categoryId);
    }

    /** 新增分类后同步加入布隆过滤器 */
    public void add(Long categoryId) {
        bloomFilter.put(categoryId);
    }
}
