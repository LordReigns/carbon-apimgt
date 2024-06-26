package org.wso2.carbon.apimgt.rest.api.devops;

import org.wso2.carbon.apimgt.rest.api.devops.*;
import org.wso2.carbon.apimgt.rest.api.devops.dto.*;

import org.apache.cxf.jaxrs.ext.MessageContext;
import org.apache.cxf.jaxrs.ext.multipart.Attachment;
import org.apache.cxf.jaxrs.ext.multipart.Multipart;

import org.wso2.carbon.apimgt.api.APIManagementException;

import org.wso2.carbon.apimgt.rest.api.devops.dto.ErrorDTO;
import org.wso2.carbon.apimgt.rest.api.devops.dto.LoggingApiInputDTO;
import org.wso2.carbon.apimgt.rest.api.devops.dto.LoggingApiOutputListDTO;

import java.util.List;

import java.io.InputStream;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;


public interface TenantLogsApiService {
      public Response tenantLogsTenantApisApiIdGet(String tenant, String apiId, MessageContext messageContext) throws APIManagementException;
      public Response tenantLogsTenantApisApiIdPut(String tenant, String apiId, LoggingApiInputDTO loggingApiInputDTO, MessageContext messageContext) throws APIManagementException;
      public Response tenantLogsTenantApisGet(String tenant, String logLevel, MessageContext messageContext) throws APIManagementException;
}
