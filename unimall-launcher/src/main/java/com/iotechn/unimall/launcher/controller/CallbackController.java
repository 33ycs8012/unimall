package com.iotechn.unimall.launcher.controller;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.binarywang.wxpay.bean.notify.WxPayNotifyResponse;
import com.github.binarywang.wxpay.bean.notify.WxPayOrderNotifyResult;
import com.github.binarywang.wxpay.exception.WxPayException;
import com.github.binarywang.wxpay.service.WxPayService;
import com.iotechn.unimall.biz.executor.GlobalExecutor;
import com.iotechn.unimall.biz.mq.DelayedMessageQueue;
import com.iotechn.unimall.biz.service.groupshop.GroupShopBizService;
import com.iotechn.unimall.biz.service.notify.AdminNotifyBizService;
import com.iotechn.unimall.biz.service.order.OrderBizService;
import com.iotechn.unimall.biz.service.product.ProductBizService;
import com.iotechn.unimall.data.domain.OrderDO;
import com.iotechn.unimall.data.domain.OrderSkuDO;
import com.iotechn.unimall.data.dto.order.OrderDTO;
import com.iotechn.unimall.data.enums.DMQHandlerType;
import com.iotechn.unimall.data.enums.OrderStatusType;
import com.iotechn.unimall.data.enums.PayChannelType;
import com.iotechn.unimall.data.enums.SpuActivityType;
import com.iotechn.unimall.data.mapper.OrderMapper;
import com.iotechn.unimall.data.mapper.OrderSkuMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Created by rize on 2019/7/10.
 */
@RestController
@RequestMapping("/cb")
public class CallbackController {

    private static final Logger logger = LoggerFactory.getLogger(CallbackController.class);

    @Autowired
    private OrderBizService orderBizService;

    @Autowired
    private OrderSkuMapper orderSkuMapper;

    @Autowired
    private WxPayService wxPayService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private ProductBizService productBizService;

    @Autowired
    private GroupShopBizService groupShopBizService;

    @Autowired
    private DelayedMessageQueue delayedMessageQueue;

    @Autowired
    private AdminNotifyBizService adminNotifyBizService;

    @RequestMapping("/wxpay")
    @Transactional(rollbackFor = Exception.class)
    public Object wxpay(@RequestBody String body) throws Exception {
        WxPayOrderNotifyResult result = null;
        try {
            result = wxPayService.parseOrderNotifyResult(body);
        } catch (WxPayException e) {
            logger.error("[????????????????????????] ??????", e);
            return WxPayNotifyResponse.fail(e.getMessage());
        }
        logger.info("???????????????????????????????????????");
        logger.info(JSONObject.toJSONString(result));

        /* ???????????????????????????????????????ID */
        // ????????????????????????????????????????????????
        String orderAbstractNo = result.getOutTradeNo();
        boolean isParent = !orderAbstractNo.contains("S");
        String payId = result.getTransactionId();

        List<OrderDO> orderDOList;
        if (isParent) {
            orderDOList = orderMapper.selectList(
                    new QueryWrapper<OrderDO>()
                            .eq("parent_order_no", orderAbstractNo));
        } else {
            orderDOList = orderMapper.selectList(
                    new QueryWrapper<OrderDO>()
                            .eq("order_no", orderAbstractNo));
        }

        if (CollectionUtils.isEmpty(orderDOList)) {
            return WxPayNotifyResponse.fail("??????????????? orderNo=" + orderAbstractNo);
        }

        int status = orderDOList.get(0).getStatus().intValue();
        int actualPrice = 0;

        for (OrderDO orderDO : orderDOList) {
            actualPrice += orderDO.getActualPrice();
            if (orderDO.getStatus().intValue() != status) {
                return WxPayNotifyResponse.fail("???????????????????????????");
            }
        }

        if (status != OrderStatusType.UNPAY.getCode()) {
            return WxPayNotifyResponse.success("????????????????????????");
        }

        Integer totalFee = result.getTotalFee();

        // ????????????????????????
        if (!totalFee.equals(actualPrice)) {
            return WxPayNotifyResponse.fail(orderAbstractNo + " : ????????????????????? totalFee=" + totalFee);
        }

        /**************** ????????????????????? ??????????????? ?????? ???????????????????????????????????? **********************/

        //1. ??????????????????
        Date now = new Date();
        OrderDO updateOrderDO = new OrderDO();
        updateOrderDO.setPayId(payId);
        updateOrderDO.setPayChannel(PayChannelType.WEPAY.getCode());
        updateOrderDO.setPayPrice(result.getTotalFee());
        updateOrderDO.setGmtPay(now);
        updateOrderDO.setGmtUpdate(now);
        updateOrderDO.setStatus(OrderStatusType.WAIT_STOCK.getCode());
        List<OrderSkuDO> orderSkuDOList;

        if (isParent) {
            // ????????????
            updateOrderDO.setSubPay(0);
            List<String> orderNos = orderDOList.stream().map(item -> item.getOrderNo()).collect(Collectors.toList());
            orderSkuDOList = orderSkuMapper.selectList(
                    new QueryWrapper<OrderSkuDO>()
                            .in("order_no", orderNos));
            if (orderSkuDOList.stream().filter(item -> (item.getActivityType() != null && item.getActivityType() == SpuActivityType.GROUP_SHOP.getCode())).count() > 0) {
                // ????????????????????? ???????????????????????????????????????
                List<OrderDO> subOrderList = orderBizService.checkOrderExistByParentNo(orderAbstractNo, null);
                // ???orderSkuList?????????????????????Key???Map
                Map<String, List<OrderSkuDO>> orderSkuMap = orderSkuDOList.stream().collect(Collectors.groupingBy(OrderSkuDO::getOrderNo));
                // ?????????????????????skuList
                for (OrderDO subOrder : subOrderList) {
                    List<OrderSkuDO> subOrderSkuList = orderSkuMap.get(subOrder.getOrderNo());
                    List<OrderSkuDO> groupShopSkuList = subOrderSkuList.stream().filter(item -> (item.getActivityType() != null && item.getActivityType() == SpuActivityType.GROUP_SHOP.getCode())).collect(Collectors.toList());
                    if (groupShopSkuList.size() > 0) {
                        // ?????????????????????
                        OrderDO groupShopUpdateDO = new OrderDO();
                        groupShopUpdateDO.setPayId(payId);
                        groupShopUpdateDO.setPayChannel(PayChannelType.WEPAY.getCode());
                        groupShopUpdateDO.setPayPrice(result.getTotalFee());
                        groupShopUpdateDO.setGmtPay(now);
                        groupShopUpdateDO.setGmtUpdate(now);
                        groupShopUpdateDO.setStatus(OrderStatusType.GROUP_SHOP_WAIT.getCode());
                        groupShopUpdateDO.setSubPay(1);
                        // ??????buyer count
                        for (OrderSkuDO orderSkuDO : groupShopSkuList) {
                            groupShopBizService.incGroupShopNum(orderSkuDO.getActivityId(), orderSkuDO.getNum());
                        }
                        orderBizService.changeOrderSubStatus(subOrder.getOrderNo(), OrderStatusType.UNPAY.getCode(), groupShopUpdateDO);
                    } else {
                        orderBizService.changeOrderSubStatus(subOrder.getOrderNo(), OrderStatusType.UNPAY.getCode(), updateOrderDO);
                    }
                }
            } else {
                // ??????????????? ?????????????????? ??????????????????
                orderBizService.changeOrderParentStatus(orderAbstractNo, OrderStatusType.UNPAY.getCode(), updateOrderDO, orderDOList.size());
            }
        } else {
            // ????????????
            updateOrderDO.setSubPay(1);
            orderSkuDOList = orderSkuMapper.selectList(
                    new QueryWrapper<OrderSkuDO>()
                            .eq("order_no", orderAbstractNo));
            List<OrderSkuDO> groupShopSkuList = orderSkuDOList.stream().filter(item -> (item.getActivityType() != null && item.getActivityType() == SpuActivityType.GROUP_SHOP.getCode())).collect(Collectors.toList());
            if (groupShopSkuList.size() > 0) {
                // ?????????????????????
                OrderDO groupShopUpdateDO = new OrderDO();
                groupShopUpdateDO.setPayId(payId);
                groupShopUpdateDO.setPayChannel(PayChannelType.WEPAY.getCode());
                groupShopUpdateDO.setPayPrice(result.getTotalFee());
                groupShopUpdateDO.setGmtPay(now);
                groupShopUpdateDO.setGmtUpdate(now);
                groupShopUpdateDO.setStatus(OrderStatusType.GROUP_SHOP_WAIT.getCode());
                groupShopUpdateDO.setSubPay(1);
                // ??????buyer count
                for (OrderSkuDO orderSkuDO : groupShopSkuList) {
                    groupShopBizService.incGroupShopNum(orderSkuDO.getActivityId(), orderSkuDO.getNum());
                }
                orderBizService.changeOrderSubStatus(orderAbstractNo, OrderStatusType.UNPAY.getCode(), groupShopUpdateDO);
            } else {
                orderBizService.changeOrderSubStatus(orderAbstractNo, OrderStatusType.UNPAY.getCode(), updateOrderDO);
            }
        }

        //2. ??????????????????
        // ???????????????????????????Spu????????????Sku?????????
        Map<Long, Integer> salesMap = orderSkuDOList.stream().collect(Collectors.toMap(OrderSkuDO::getSpuId, OrderSkuDO::getNum, (k1, k2) -> k1.intValue() + k2.intValue()));
        productBizService.incSpuSales(salesMap);


        //3. ????????????????????? & ??????????????????????????????????????????
        Map<String, List<OrderSkuDO>> orderSkuMap = orderSkuDOList.stream().collect(Collectors.groupingBy(OrderSkuDO::getOrderNo));
        Map<String, List<OrderDO>> orderMap = orderDOList.stream().collect(Collectors.groupingBy(OrderDO::getOrderNo));
        for (String subOrderNo : orderSkuMap.keySet()) {
            OrderDTO finalOrderDTO = new OrderDTO();
            OrderDO orderDO = orderMap.get(subOrderNo).get(0);
            BeanUtils.copyProperties(orderDO, finalOrderDTO);
            finalOrderDTO.setPayChannel(PayChannelType.WEPAY.getCode());
            finalOrderDTO.setSkuList(orderSkuMap.get(subOrderNo));
            GlobalExecutor.execute(() -> {
                adminNotifyBizService.newOrder(finalOrderDTO);
            });
            delayedMessageQueue.deleteTask(DMQHandlerType.ORDER_AUTO_CANCEL.getCode(), subOrderNo);
            logger.info("[????????????????????????] orderNo:" + subOrderNo);
        }
        return WxPayNotifyResponse.success("????????????");
    }

}
