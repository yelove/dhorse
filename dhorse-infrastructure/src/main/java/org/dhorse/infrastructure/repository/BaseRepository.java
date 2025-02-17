package org.dhorse.infrastructure.repository;

import java.util.List;
import java.util.stream.Collectors;

import org.dhorse.api.enums.MessageCodeEnum;
import org.dhorse.infrastructure.param.PageParam;
import org.dhorse.infrastructure.repository.mapper.CustomizedBaseMapper;
import org.dhorse.infrastructure.repository.po.BasePO;
import org.dhorse.infrastructure.utils.BeanUtils;
import org.dhorse.infrastructure.utils.ClassUtils;
import org.dhorse.infrastructure.utils.LogUtils;
import org.dhorse.infrastructure.utils.QueryHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

public abstract class BaseRepository<P extends PageParam, E extends BasePO> {

	private static final Logger logger = LoggerFactory.getLogger(BaseRepository.class);
	
	public long count(P bizParam) {
		QueryWrapper<E> queryWrapper = buildQueryWrapper(bizParam, null);
		Long count = getMapper().selectCount(queryWrapper);
		if (count == null) {
			return 0L;
		}
		return count.longValue();
	}

	public List<E> list(P bizParam) {
		QueryWrapper<E> queryWrapper = buildQueryWrapper(bizParam, "update_time");
		return getMapper().selectList(queryWrapper);
	}

	public IPage<E> page(P bizParam) {
		IPage<E> page = new Page<>(bizParam.getPageNum(), bizParam.getPageSize());
		QueryWrapper<E> queryWrapper = buildQueryWrapper(bizParam, "update_time");
		return getMapper().selectPage(page, queryWrapper);
	}
	
	public E query(P bizParam) {
		List<E> list = list(bizParam);
		if(CollectionUtils.isEmpty(list)) {
			return null;
		}
		return list.get(0);
	}

	public E queryById(String id) {
		if(id == null) {
			return null;
		}
		QueryWrapper<E> wrapper = new QueryWrapper<>();
		wrapper.eq("id", id);
		wrapper.eq("deletion_status", 0);
		return getMapper().selectOne(wrapper);
	}

	public String add(P bizParam) {
		E e = param2Entity(bizParam);
		getMapper().insert(e);
		return e.getId();
	}
	
	public List<String> addList(List<P> bizParams) {
		List<E> es = bizParams.stream().map(b -> param2Entity(b)).collect(Collectors.toList());
		return es.stream().map(e -> {
			getMapper().insert(e);
			return e.getId();
		}).collect(Collectors.toList());
	}
	
	public boolean update(P bizParam) {
		return getMapper().update(param2Entity(bizParam), buildUpdateWrapper(bizParam)) > 0 ? true : false;
	}

	public boolean updateById(P bizParam) {
		UpdateWrapper<E> wrapper = new UpdateWrapper<>();
		wrapper.eq("id", bizParam.getId());
		wrapper.eq("deletion_status", 0);
		return getMapper().update(param2Entity(bizParam), wrapper) > 0 ? true : false;
	}

	public boolean delete(String id) {
		E po = ClassUtils.newParameterizedTypeInstance(getClass().getGenericSuperclass(), 1);
		po.setId(id);
		po.setDeletionStatus(1);
		return getMapper().updateById(po) > 0 ? true : false;
	}
	
	protected E param2Entity(P bizParam) {
		E po = ClassUtils.newParameterizedTypeInstance(getClass().getGenericSuperclass(), 1);
		BeanUtils.copyProperties(bizParam, po);
		return po;
	}

	protected QueryWrapper<E> buildQueryWrapper(P bizParam, String orderField) {
		return QueryHelper.buildQueryWrapper(param2Entity(bizParam), orderField);
	}

	protected UpdateWrapper<E> buildUpdateWrapper(P bizParam) {
		return QueryHelper.buildUpdateWrapper(updateCondition(bizParam));
	}

	protected void validateApp(String appId) {
		if(appId == null) {
			LogUtils.throwException(logger, MessageCodeEnum.APP_ID_IS_NULL);
		}
//		QueryWrapper<AppPO> wrapper = new QueryWrapper<>();
//		wrapper.eq("id", appId);
//		wrapper.eq("deletion_status", 0);
//		if(appMapper.selectCount(wrapper) == 0) {
//			LogUtils.throwException(logger, MessageCodeEnum.APP_IS_INEXISTENCE);
//		}
	}
	
	protected abstract E updateCondition(P bizParam);

	protected abstract CustomizedBaseMapper<E> getMapper();
}
