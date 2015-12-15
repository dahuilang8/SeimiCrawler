package cn.wanghaomiao.seimi.core;

import cn.wanghaomiao.seimi.annotation.Interceptor;
import cn.wanghaomiao.seimi.def.BaseSeimiCrawler;
import cn.wanghaomiao.seimi.http.HttpClientFactory;
import cn.wanghaomiao.seimi.http.HttpMethod;
import cn.wanghaomiao.seimi.struct.BodyType;
import cn.wanghaomiao.seimi.struct.CrawlerModel;
import cn.wanghaomiao.seimi.struct.Request;
import cn.wanghaomiao.seimi.struct.Response;
import cn.wanghaomiao.seimi.utils.StructValidator;
import cn.wanghaomiao.xpath.exception.NoSuchAxisException;
import cn.wanghaomiao.xpath.exception.NoSuchFunctionException;
import cn.wanghaomiao.xpath.exception.XpathSyntaxErrorException;
import cn.wanghaomiao.xpath.model.JXDocument;
import com.alibaba.fastjson.JSON;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpCoreContext;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author 汪浩淼 [et.tw@163.com]
 * @since 2015/8/21.
 */
public class SeimiProcessor implements Runnable {
    private SeimiQueue queue;
    private List<SeimiInterceptor> interceptors;
    private CrawlerModel crawlerModel;
    private BaseSeimiCrawler crawler;
    private Logger logger = LoggerFactory.getLogger(getClass());
    public SeimiProcessor(List<SeimiInterceptor> interceptors,CrawlerModel crawlerModel){
        this.queue = crawlerModel.getQueueInstance();
        this.interceptors = interceptors;
        this.crawlerModel = crawlerModel;
        this.crawler = crawlerModel.getInstance();
    }
    private Pattern metaRefresh = Pattern.compile("<(?:META|meta|Meta)\\s+(?:HTTP-EQUIV|http-equiv)\\s*=\\s*\"refresh\".*URL=(.*)\">");
    @Override
    public void run() {
        while (true){
            Request request = queue.bPop(crawlerModel.getCrawlerName());
            try {
                if (request==null){
                    continue;
                }
                if (crawlerModel==null){
                    logger.error("No such crawler name:'{}'",request.getCrawlerName());
                    continue;
                }
                if (request.isStop()){
                    logger.info("SeimiProcessor[{}] will stop!",Thread.currentThread().getName());
                    break;
                }
                //对请求开始校验
                if (!StructValidator.validateAnno(request)){
                    logger.warn("Request={} is illegal",JSON.toJSONString(request));
                    continue;
                }
                if (!StructValidator.validateAllowRules(crawler.allowRules(),request.getUrl())){
                    logger.warn("Request={} will be dropped by allowRules=[{}]",JSON.toJSONString(request),StringUtils.join(crawler.allowRules(),","));
                    continue;
                }
                if (StructValidator.validateDenyRules(crawler.denyRules(),request.getUrl())){
                    logger.warn("Request={} will be dropped by denyRules=[{}]",JSON.toJSONString(request),StringUtils.join(crawler.denyRules(),","));
                    continue;
                }
                //如果启用了系统级去重机制则判断一个Request是否已经被处理过了
                if (crawlerModel.isUseUnrepeated() && request.getCurrentReqCount()>=request.getMaxReqCount() && queue.isProcessed(request)){
                    logger.info("This request has bean processed,so current request={} will be dropped!", JSON.toJSONString(request));
                    continue;
                }
                HttpClient hc;
                if (crawlerModel.isUseCookie()){
                    hc = HttpClientFactory.getHttpClient(10000,crawler.getCookieStore());
                }else {
                    hc = HttpClientFactory.getHttpClient();
                }
                RequestConfig config = RequestConfig.custom().setProxy(crawlerModel.getProxy()).build();
                RequestBuilder requestBuilder;
                if (HttpMethod.POST.equals(request.getHttpMethod())){
                    requestBuilder = RequestBuilder.post().setUri(request.getUrl());
                }else {
                    requestBuilder = RequestBuilder.get().setUri(request.getUrl());
                }
                if (request.getParams()!=null){
                    for (Map.Entry<String,String> entry:request.getParams().entrySet()){
                        requestBuilder.addParameter(entry.getKey(),entry.getValue());
                    }
                }
                requestBuilder.setConfig(config).setHeader("User-Agent",crawler.getUserAgent());
                HttpContext httpContext = new BasicHttpContext();
                HttpResponse httpResponse = hc.execute(requestBuilder.build(),httpContext);
                Response seimiResponse = renderResponse(httpResponse,request,httpContext);
                Matcher mm = metaRefresh.matcher(seimiResponse.getContent());
                while (mm.find()){
                    String nextUrl = mm.group(1).replaceAll("'","");
                    if (!nextUrl.startsWith("http")){
                        String prefix = getRealUrl(httpContext);
                        nextUrl = prefix + nextUrl;
                    }
                    logger.info("Seimi refresh url to={} from={}",nextUrl,requestBuilder.getUri());
                    requestBuilder.setUri(nextUrl);
                    httpResponse = hc.execute(requestBuilder.build(),httpContext);
                    seimiResponse = renderResponse(httpResponse,request,httpContext);
                    mm = metaRefresh.matcher(seimiResponse.getContent());
                }
                Method requestCallback = crawlerModel.getMemberMethods().get(request.getCallBack());
                if (requestCallback==null){
                    continue;
                }
                for (SeimiInterceptor interceptor : interceptors) {
                    Interceptor interAnno = interceptor.getClass().getAnnotation(Interceptor.class);
                    if (interAnno.everyMethod()||requestCallback.isAnnotationPresent(interceptor.getTargetAnnotationClass())||crawlerModel.getClazz().isAnnotationPresent(interceptor.getTargetAnnotationClass())){
                        interceptor.before(requestCallback, seimiResponse);
                    }
                }
                if (crawlerModel.getDelay()>0){
                    TimeUnit.SECONDS.sleep(crawlerModel.getDelay());
                }
                requestCallback.invoke(crawlerModel.getInstance(),seimiResponse);
                for (SeimiInterceptor interceptor : interceptors) {
                    Interceptor interAnno = interceptor.getClass().getAnnotation(Interceptor.class);
                    if (interAnno.everyMethod()||requestCallback.isAnnotationPresent(interceptor.getTargetAnnotationClass())||crawlerModel.getClazz().isAnnotationPresent(interceptor.getTargetAnnotationClass())){
                        interceptor.after(requestCallback, seimiResponse);
                    }
                }
                logger.debug("Crawler[{}] ,url={} ,responseStatus={}",crawlerModel.getCrawlerName(),request.getUrl(),httpResponse.getStatusLine().getStatusCode());
            }catch (Exception e){
                if (request.getCurrentReqCount()<request.getMaxReqCount()){
                    request.incrReqCount();
                    queue.push(request);
                    logger.info("Request process error,req will go into queue again,url={},maxReqCount={],currentReqCount={}",request.getUrl(),request.getMaxReqCount(),request.getCurrentReqCount());
                }else if (request.getCurrentReqCount()>= request.getMaxReqCount()&& request.getMaxReqCount()>0){
                    crawler.handleErrorRequest(request);
                }
                logger.error(e.getMessage(),e);
            }
        }
    }

    private Response renderResponse(HttpResponse httpResponse,Request request,HttpContext httpContext){
        Response seimiResponse = new Response();
        HttpEntity entity = httpResponse.getEntity();
        seimiResponse.setHttpResponse(httpResponse);
        seimiResponse.setReponseEntity(entity);
        seimiResponse.setRealUrl(getRealUrl(httpContext));
        seimiResponse.setUrl(request.getUrl());
        seimiResponse.setRequest(request);
        if (entity != null) {
            Header referer = httpResponse.getFirstHeader("Referer");
            if (referer!=null){
                seimiResponse.setReferer(referer.getValue());
            }
            if (!entity.getContentType().getValue().contains("image")){
                seimiResponse.setBodyType(BodyType.TEXT);
                try {
                    seimiResponse.setData(EntityUtils.toByteArray(entity));
                    ContentType contentType = ContentType.get(entity);
                    Charset charset = contentType.getCharset();
                    if (charset==null){
                        seimiResponse.setContent(new String(seimiResponse.getData(),"ISO-8859-1"));
                        String docCharset = renderRealCharset(seimiResponse);
                        seimiResponse.setContent(new String(seimiResponse.getContent().getBytes("ISO-8859-1"),docCharset));
                    }else {
                        seimiResponse.setContent(new String(seimiResponse.getData(),charset));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    logger.error("no content data");
                }
            }else {
                seimiResponse.setBodyType(BodyType.BINARY);
                try {
                    seimiResponse.setData(EntityUtils.toByteArray(entity));
                    seimiResponse.setContent(StringUtils.substringAfterLast(request.getUrl(),"/"));
                } catch (Exception e) {
                    logger.error("no data can be read from httpResponse");
                }
            }
        }
        return seimiResponse;
    }

    private String renderRealCharset(Response response) throws NoSuchFunctionException, XpathSyntaxErrorException, NoSuchAxisException {
        String charset;
        JXDocument doc = response.document();
        charset = StringUtils.join(doc.sel("//meta[@charset]/@charset"),"").trim();
        if (StringUtils.isBlank(charset)){
            charset = StringUtils.join(doc.sel("//meta[@http-equiv='charset']/@content"),"").trim();
        }
        if (StringUtils.isBlank(charset)){
            String ct = StringUtils.join(doc.sel("//meta[@http-equiv='Content-Type']/@content|//meta[@http-equiv='content-type']/@content"),"").trim();
            if (ct.toLowerCase().contains("charset")){
                charset = ct.split(";")[1].trim().split("=")[1];
            }
        }
        return StringUtils.isNotBlank(charset)?charset:"UTF-8";
    }

    private String getRealUrl(HttpContext httpContext){
        Object target = httpContext.getAttribute(HttpCoreContext.HTTP_TARGET_HOST);
        Object reqUri = httpContext.getAttribute(HttpCoreContext.HTTP_REQUEST);
        if (target==null||reqUri==null){
            return null;
        }
        HttpHost t = (HttpHost) target;
        HttpUriRequest r = (HttpUriRequest)reqUri;
        return r.getURI().isAbsolute()?r.getURI().toString():t.toString()+r.getURI().toString();
    }
}
