package com.example.ledger.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ledger.model.TxnLogEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TxnLogMapper extends BaseMapper<TxnLogEntity> {
}
