package com.iotechn.unimall.admin.api.address;

import com.iotechn.unimall.core.annotation.HttpMethod;
import com.iotechn.unimall.core.annotation.HttpOpenApi;
import com.iotechn.unimall.core.exception.ServiceException;
import com.iotechn.unimall.data.domain.AddressDO;
import com.iotechn.unimall.data.model.Page;

/**
 * Description:
 * User: rize
 * Date: 2020/8/12
 * Time: 11:31
 */
@HttpOpenApi(group = "admin.address", description = "管理员地址管理")
public interface AdminAddressService {

    @HttpMethod(description = "列表")
    public Page<AddressDO> list(

    ) throws ServiceException;

}
