package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.entity.DishFlavor;
import com.sky.entity.Setmeal;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.mapper.DishFlavorMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.cache.UserDishCacheService;
import com.sky.result.PageResult;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DishServiceImpl implements DishService {

    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private DishFlavorMapper dishFlavorMapper;
    @Autowired
    private SetmealDishMapper setmealDishMapper;
    @Autowired
    private SetmealMapper setmealMapper;
    @Autowired
    private UserDishCacheService userDishCacheService;

    /**
     * 新增菜品和对应的口味
     *
     * @param dishDTO
     */
    @Transactional
    @CacheEvict(cacheManager = "caffeineCacheManager", cacheNames = "adminDishPage", allEntries = true)
    public void saveWithFlavor(DishDTO dishDTO) {

        Dish dish = new Dish();

        BeanUtils.copyProperties(dishDTO, dish);

        //向菜品表插入1条数据
        dishMapper.insert(dish);

        //获取insert语句生成的主键值
        Long dishId = dish.getId();

        List<DishFlavor> flavors = dishDTO.getFlavors();
        if (flavors != null && flavors.size() > 0) {
            flavors.forEach(dishFlavor -> {
                dishFlavor.setDishId(dishId);
            });
            //向口味表插入n条数据
            dishFlavorMapper.insertBatch(flavors);
        }

        //二次开发任务1：清理用户端多级缓存（精准清理：新增菜品只影响其所在分类）
        userDishCacheService.evictCategory(dishDTO.getCategoryId());
    }

    /**
     * 菜品分页查询（二次开发任务1：加 Caffeine 本地缓存）
     * key = 查询参数组合；管理端增删改菜品时通过 @CacheEvict(allEntries=true) 清空
     */
    @Cacheable(cacheManager = "caffeineCacheManager", cacheNames = "adminDishPage",
            key = "#dishPageQueryDTO.page + ':' + #dishPageQueryDTO.pageSize + ':' + " +
                    "(#dishPageQueryDTO.name != null ? #dishPageQueryDTO.name : '') + ':' + " +
                    "(#dishPageQueryDTO.categoryId != null ? #dishPageQueryDTO.categoryId : '') + ':' + " +
                    "(#dishPageQueryDTO.status != null ? #dishPageQueryDTO.status : '')",
            unless = "#result == null")
    public PageResult pageQuery(DishPageQueryDTO dishPageQueryDTO) {
        PageHelper.startPage(dishPageQueryDTO.getPage(), dishPageQueryDTO.getPageSize());
        Page<DishVO> page = dishMapper.pageQuery(dishPageQueryDTO);
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 菜品批量删除
     *
     * @param ids
     */
    @Transactional
    @CacheEvict(cacheManager = "caffeineCacheManager", cacheNames = "adminDishPage", allEntries = true)
    public void deleteBatch(List<Long> ids) {
        //判断当前菜品是否能够删除---是否存在起售中的菜品？？
        Set<Long> categoryIds = new HashSet<>();
        for (Long id : ids) {
            Dish dish = dishMapper.getById(id);
            if (dish.getStatus() == StatusConstant.ENABLE) {
                //当前菜品处于起售中，不能删除
                throw new DeletionNotAllowedException(MessageConstant.DISH_ON_SALE);
            }
            //收集受影响分类（精准清理缓存用）
            categoryIds.add(dish.getCategoryId());
        }

        //判断当前菜品是否能够删除---是否被套餐关联了？？
        List<Long> setmealIds = setmealDishMapper.getSetmealIdsByDishIds(ids);
        if (setmealIds != null && setmealIds.size() > 0) {
            //当前菜品被套餐关联了，不能删除
            throw new DeletionNotAllowedException(MessageConstant.DISH_BE_RELATED_BY_SETMEAL);
        }

        //删除菜品表中的菜品数据
        for (Long id : ids) {
            dishMapper.deleteById(id);
            //删除菜品关联的口味数据
            dishFlavorMapper.deleteByDishId(id);
        }

        //二次开发任务1：清理用户端多级缓存（精准清理：批量删除只影响涉及分类）
        userDishCacheService.evictCategory(categoryIds.toArray(new Long[0]));
    }

    /**
     * 根据id查询菜品和对应的口味数据
     *
     * @param id
     * @return
     */
    public DishVO getByIdWithFlavor(Long id) {
        //根据id查询菜品数据
        Dish dish = dishMapper.getById(id);

        //根据菜品id查询口味数据
        List<DishFlavor> dishFlavors = dishFlavorMapper.getByDishId(id);

        //将查询到的数据封装到VO
        DishVO dishVO = new DishVO();
        BeanUtils.copyProperties(dish, dishVO);
        dishVO.setFlavors(dishFlavors);

        return dishVO;
    }

    /**
     * 根据id修改菜品基本信息和对应的口味信息
     *
     * @param dishDTO
     */
    @CacheEvict(cacheManager = "caffeineCacheManager", cacheNames = "adminDishPage", allEntries = true)
    public void updateWithFlavor(DishDTO dishDTO) {
        //查询旧数据：菜品换分类时，新旧两个分类的缓存都要清理
        Dish dishDB = dishMapper.getById(dishDTO.getId());
        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO, dish);

        //修改菜品表基本信息
        dishMapper.update(dish);

        //删除原有的口味数据
        dishFlavorMapper.deleteByDishId(dishDTO.getId());

        //重新插入口味数据
        List<DishFlavor> flavors = dishDTO.getFlavors();
        if (flavors != null && flavors.size() > 0) {
            flavors.forEach(dishFlavor -> {
                dishFlavor.setDishId(dishDTO.getId());
            });
            //向口味表插入n条数据
            dishFlavorMapper.insertBatch(flavors);
        }

        //二次开发任务1：清理用户端多级缓存（精准清理：修改影响旧分类和新分类）
        userDishCacheService.evictCategory(dishDB.getCategoryId(), dishDTO.getCategoryId());
    }

    /**
     * 菜品起售停售
     *
     * @param status
     * @param id
     */
    @Transactional
    @CacheEvict(cacheManager = "caffeineCacheManager", cacheNames = "adminDishPage", allEntries = true)
    public void startOrStop(Integer status, Long id) {
        //查询旧数据获取分类 id（精准清理缓存用）
        Dish dishDB = dishMapper.getById(id);
        Dish dish = Dish.builder()
                .id(id)
                .status(status)
                .build();
        dishMapper.update(dish);

        if (status == StatusConstant.DISABLE) {
            // 如果是停售操作，还需要将包含当前菜品的套餐也停售
            List<Long> dishIds = new ArrayList<>();
            dishIds.add(id);
            // select setmeal_id from setmeal_dish where dish_id in (?,?,?)
            List<Long> setmealIds = setmealDishMapper.getSetmealIdsByDishIds(dishIds);
            if (setmealIds != null && setmealIds.size() > 0) {
                for (Long setmealId : setmealIds) {
                    Setmeal setmeal = Setmeal.builder()
                            .id(setmealId)
                            .status(StatusConstant.DISABLE)
                            .build();
                    setmealMapper.update(setmeal);
                }
            }
        }

        //二次开发任务1：清理用户端多级缓存（精准清理：起售停售只影响其所在分类）
        userDishCacheService.evictCategory(dishDB.getCategoryId());
    }

    /**
     * 根据分类id查询菜品
     *
     * @param categoryId
     * @return
     */
    public List<Dish> list(Long categoryId) {
        Dish dish = Dish.builder()
                .categoryId(categoryId)
                .status(StatusConstant.ENABLE)
                .build();
        return dishMapper.list(dish);
    }

    /**
     * 条件查询菜品和口味（二次开发任务1：接入多级缓存）
     * 读取链路：L1 Caffeine(30s) → L2 Redis(30min+随机偏移) → 布隆过滤器 → 互斥锁回源数据库
     * 缓存逻辑全部在 UserDishCacheService 中，本方法只保留数据库回源逻辑
     * @param dish
     * @return
     */
    public List<DishVO> listWithFlavor(Dish dish) {
        return userDishCacheService.listWithCache(dish.getCategoryId(),
                () -> loadDishWithFlavorFromDb(dish.getCategoryId()));
    }

    /**
     * 纯数据库回源：查起售菜品 + 口味（二次开发性能优化：口味 IN 批量查，消灭 N+1）
     * 改造前：1 次查菜 + 逐菜查口味 = 1+N 次往返（61 次 ≈ 458ms）
     * 改造后：1 次查菜 + 1 次口味 IN 批量查 = 2 次往返（配合 idx_dish_id 索引 ≈ 10~20ms）
     * @param categoryId
     * @return
     */
    private List<DishVO> loadDishWithFlavorFromDb(Long categoryId) {
        Dish dish = Dish.builder()
                .categoryId(categoryId)
                .status(StatusConstant.ENABLE)
                .build();
        List<Dish> dishList = dishMapper.list(dish);
        if (dishList.isEmpty()) {
            // 空分类直接返回，避免 in () 产生 SQL 语法错误
            return new ArrayList<>();
        }

        // 收集本结果集全部菜品 id，一次 SQL 拿回所有口味
        List<Long> dishIds = dishList.stream().map(Dish::getId).collect(Collectors.toList());

        // 按 dishId 在内存中分组：{65=[口味...], 66=[口味...], ...}
        Map<Long, List<DishFlavor>> flavorMap = dishFlavorMapper.getByDishIds(dishIds)
                .stream()
                .collect(Collectors.groupingBy(DishFlavor::getDishId));

        List<DishVO> dishVOList = new ArrayList<>();

        for (Dish d : dishList) {
            DishVO dishVO = new DishVO();
            BeanUtils.copyProperties(d,dishVO);

            // 从内存 Map 取该菜的口味（查不到说明这道菜没有口味记录，给空列表）
            dishVO.setFlavors(flavorMap.getOrDefault(d.getId(), Collections.emptyList()));
            dishVOList.add(dishVO);
        }

        return dishVOList;
    }
}
