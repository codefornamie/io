/**
 * personium.io
 * Copyright 2014 FUJITSU LIMITED
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fujitsu.dc.core.rs.box;

import java.io.InputStream;
import java.io.Reader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.apache.http.HttpStatus;
import org.apache.wink.webdav.WebDAVMethod;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fujitsu.dc.common.utils.DcCoreUtils;
import com.fujitsu.dc.core.DcCoreException;
import com.fujitsu.dc.core.annotations.ACL;
import com.fujitsu.dc.core.auth.AccessContext;
import com.fujitsu.dc.core.auth.BoxPrivilege;
import com.fujitsu.dc.core.bar.BarFileInstaller;
import com.fujitsu.dc.core.eventbus.DcEventBus;
import com.fujitsu.dc.core.eventbus.JSONEvent;
import com.fujitsu.dc.core.model.Box;
import com.fujitsu.dc.core.model.BoxCmp;
import com.fujitsu.dc.core.model.BoxRsCmp;
import com.fujitsu.dc.core.model.Cell;
import com.fujitsu.dc.core.model.DavCmp;
import com.fujitsu.dc.core.model.DavRsCmp;
import com.fujitsu.dc.core.model.ModelFactory;
import com.fujitsu.dc.core.model.ctl.Event;
import com.fujitsu.dc.core.model.ctl.Event.LEVEL;
import com.fujitsu.dc.core.model.progress.Progress;
import com.fujitsu.dc.core.model.progress.ProgressInfo;
import com.fujitsu.dc.core.model.progress.ProgressManager;
import com.fujitsu.dc.core.rs.cell.CellCtlResource;
import com.fujitsu.dc.core.rs.cell.EventResource;
import com.fujitsu.dc.core.rs.odata.ODataEntityResource;

/**
 * Boxを担当するJAX-RSリソース.
 */
public final class BoxResource {
    static Logger log = LoggerFactory.getLogger(BoxResource.class);
    String boxName;

    Cell cell;
    Box box;
    AccessContext accessContext;
    DavRsCmp davRsCmp;
    DavCmp davCmp;
    DavRsCmp cellRsCmp; // for box Install

    /**
     * コンストラクタ.
     * @param cell CELL Object
     * @param boxName Box Name
     * @param cellRsCmp cellRsCmp
     * @param accessContext AccessContextオブジェクト
     * @param request HTTPリクエスト
     * @param jaxRsRequest JAX-RS用HTTPリクエスト
     */
    public BoxResource(final Cell cell, final String boxName, final AccessContext accessContext,
            final DavRsCmp cellRsCmp, final HttpServletRequest request, Request jaxRsRequest) {
        // 親はなし。パス名としてとりあえずboxNameをいれておく。
        this.cell = cell;
        this.boxName = boxName;
        // this.path= path;
        this.accessContext = accessContext;

        // Boxの存在確認
        // 本クラスではBoxが存在していることを前提としているため、Boxがない場合はエラーとする。
        // ただし、boxインストールではBoxがないことを前提としているため、以下の条件に合致する場合は処理を継続する。
        // －HTTPメソッドが MKCOL である。かつ、
        // －PathInfoが インストール先Box名 で終了している。
        // （CollectionへのMKCOLの場合があるため、boxインストールであることを確認する）
        this.box = this.cell.getBoxForName(boxName);
        // boxインストールではCellレベルで動作させる必要がある。
        this.cellRsCmp = cellRsCmp;
        if (this.box != null) {
            // このBoxが存在するときのみBoxCmpが必要
            this.davCmp = ModelFactory.boxCmp(this.box);
            this.davRsCmp = new BoxRsCmp(davCmp, this.cell, this.accessContext, this.box);
        } else {
            String reqPathInfo = request.getPathInfo();
            if (!reqPathInfo.endsWith("/")) {
                reqPathInfo += "/";
            }
            String pathForBox = boxName;
            if (!pathForBox.endsWith("/")) {
                pathForBox += "/";
            }
            if (!("MKCOL".equals(jaxRsRequest.getMethod()) && reqPathInfo.endsWith(pathForBox))) {
                throw DcCoreException.Dav.BOX_NOT_FOUND.params(this.cell.getUrl() + boxName);
            }
        }

    }

    /*
     * このリソースのURLを返します. 本クラスではBoxのURLが返ります。
     * @see com.fujitsu.dc.core.rs.box.AbstractDavResource#getPathList()
     */

    /**
     * 現在のリソースの一つ下位パスを担当するJax-RSリソースを返す.
     * @param nextPath 一つ下のパス名
     * @param request リクエスト
     * @return 下位パスを担当するJax-RSリソースオブジェクト
     */
    @Path("{nextPath}")
    public Object nextPath(@PathParam("nextPath") final String nextPath,
            @Context HttpServletRequest request) {
        return this.davRsCmp.nextPath(nextPath, request);
    }

    /**
     * @return DavRsCmp
     */
    public DavRsCmp getDavRsCmp() {
        return this.davRsCmp;
    }

    /**
     * Boxのパス名を返します.
     * @return Boxのパス名
     */
    public String getName() {
        return this.boxName;
    }

    /**
     * @return Box オブジェクト
     */
    public Box getBox() {
        return this.box;
    }

    /**
     * @return BoxCmp オブジェクト.
     */
    public BoxCmp getCmp() {
        return (BoxCmp) this.davCmp;
    }

    /**
     * @return AccessContext オブジェクト
     */
    public AccessContext getAccessContext() {
        return accessContext;
    }

    /**
     * GET リクエストの処理 .
     * @return JAX-RS Response
     */
    @GET
    public Response get() {

        // アクセス制御
        this.davRsCmp.checkAccessContext(this.davRsCmp.getAccessContext(), BoxPrivilege.READ);

        // キャッシュからboxインストールの非同期処理状況を取得する。
        // この際、nullが返ってきた場合は、boxインストールが実行されていないか、
        // 実行されたがキャッシュの有効期限が切れたとみなす。
        String key = "box-" + this.box.getId();
        Progress progress = ProgressManager.getProgress(key);
        if (progress == null) {
            JSONObject response = createNotRequestedResponse();
            return Response.ok().entity(response.toJSONString()).build();
        }

        String jsonString = progress.getValue();
        JSONObject jsonObj = null;
        try {
            jsonObj = (JSONObject) (new JSONParser()).parse(jsonString);
        } catch (ParseException e) {
            throw DcCoreException.Server.DATA_STORE_UNKNOWN_ERROR.reason(e);
        }

        // キャッシュから取得できたが、boxインストールの処理状況ではない場合
        JSONObject barInfo = (JSONObject) jsonObj.get("barInfo");
        if (barInfo == null) {
            log.info("cache(" + key + "): process" + (String) jsonObj.get("process"));
            JSONObject response = createNotRequestedResponse();
            return Response.ok().entity(response.toJSONString()).build();
        }

        // boxインストールの処理状況に合わせてレスポンスを作成する。
        JSONObject response = createResponse(barInfo);
        return Response.ok().entity(response.toJSONString()).build();
    }

    /**
     * boxインストールが実行されていないか、実行されたがキャッシュの有効期限が切れた場合のレスポンスを作成する.
     * @return レスポンス用JSONオブジェクト
     */
    @SuppressWarnings("unchecked")
    private JSONObject createNotRequestedResponse() {
        JSONObject response = new JSONObject();
        response.put("status", ProgressInfo.STATUS.COMPLETED.value());
        response.put("schema", this.getBox().getSchema());

        SimpleDateFormat sdfIso8601ExtendedFormatUtc = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        sdfIso8601ExtendedFormatUtc.setTimeZone(TimeZone.getTimeZone("UTC"));
        String installedAt = sdfIso8601ExtendedFormatUtc.format(new Date(this.getBox().getPublished()));
        response.put("installed_at", installedAt);
        return response;
    }

    /**
     * boxインストールが実行されていないか、実行されたがキャッシュの有効期限が切れた場合のレスポンスを作成する.
     * @return レスポンス用JSONオブジェクト
     */
    @SuppressWarnings("unchecked")
    private JSONObject createResponse(JSONObject values) {
        JSONObject response = new JSONObject();
        response.putAll(values);
        response.remove("cell_id");
        response.remove("box_id");
        response.put("schema", this.getBox().getSchema());
        ProgressInfo.STATUS status = ProgressInfo.STATUS.valueOf((String) values.get("status"));
        if (status == ProgressInfo.STATUS.COMPLETED) {
            response.remove("progress");
            String startedAt = (String) response.remove("started_at");
            response.put("installed_at", startedAt);
        }
        response.put("status", status.value());
        return response;
    }

    /**
     * PROPFINDメソッドの処理.
     * @param requestBodyXml Request Body
     * @param depth Depth Header
     * @param contentLength Content-Length Header
     * @param transferEncoding Transfer-Encoding Header
     * @return JAX-RS Response
     */
    @WebDAVMethod.PROPFIND
    public Response propfind(final Reader requestBodyXml,
            @HeaderParam(DcCoreUtils.HttpHeaders.DEPTH) final String depth,
            @HeaderParam(HttpHeaders.CONTENT_LENGTH) final Long contentLength,
            @HeaderParam("Transfer-Encoding") final String transferEncoding) {

        return this.davRsCmp.doPropfind(requestBodyXml, depth, contentLength, transferEncoding,
                BoxPrivilege.READ_PROPERTIES, BoxPrivilege.READ_ACL);
    }

    /**
     * PROPPATCHメソッドの処理.
     * @param requestBodyXml Request Body
     * @return JAX-RS Response
     */
    @WebDAVMethod.PROPPATCH
    public Response proppatch(final Reader requestBodyXml) {
        // アクセス制御
        this.davRsCmp.checkAccessContext(this.getAccessContext(), BoxPrivilege.WRITE_PROPERTIES);
        return this.davRsCmp.doProppatch(requestBodyXml);
    }

    /**
     * OPTIONSメソッド.
     * @return JAX-RS Response
     */
    @OPTIONS
    public Response options() {
        return this.davRsCmp.options();
    }

    /**
     * ACLメソッドの処理. ACLの設定を行う.
     * @param reader 設定XML
     * @return JAX-RS Response
     */
    @ACL
    public Response acl(final Reader reader) {
        // アクセス制御
        this.davRsCmp.checkAccessContext(this.davRsCmp.getAccessContext(), BoxPrivilege.WRITE_ACL);
        return this.davRsCmp.doAcl(reader);
    }

    /**
     * MKCOLメソッドの処理. boxインストールを行う.
     * @param uriInfo UriInfo
     * @param dcCredHeader dcCredHeader
     * @param contentType Content-Typeヘッダの値
     * @param contentLength Content-Lengthヘッダの値
     * @param requestKey イベントログに出力するRequestKeyフィールドの値
     * @param inStream HttpリクエストのInputStream
     * @return JAX-RS Response
     */
    @WebDAVMethod.MKCOL
    public Response mkcol(
            @Context final UriInfo uriInfo,
            @HeaderParam(DcCoreUtils.HttpHeaders.X_DC_CREDENTIAL) final String dcCredHeader,
            @HeaderParam(HttpHeaders.CONTENT_TYPE) final String contentType,
            @HeaderParam(HttpHeaders.CONTENT_LENGTH) final String contentLength,
            @HeaderParam(DcCoreUtils.HttpHeaders.X_DC_REQUESTKEY) String requestKey,
            final InputStream inStream) {

        DcEventBus eventBus = new DcEventBus(this.cell);
        Event event = null;
        Response res = null;
        try {
            // ログファイル出力
            JSONEvent reqBody = new JSONEvent();
            reqBody.setAction(WebDAVMethod.MKCOL.toString());
            reqBody.setLevel(LEVEL.INFO);
            reqBody.setObject(this.cell.getUrl() + boxName);
            reqBody.setResult("");
            // X-Dc-RequestKeyの解析（指定なしの場合にデフォルト値を補充）
            requestKey = EventResource.validateXDcRequestKey(requestKey);
            // TODO findBugs対策↓
            log.debug(requestKey);

            event = EventResource.createEvent(reqBody, requestKey, this.accessContext);
            // eventBus.outputEventLog(event);

            if (Box.DEFAULT_BOX_NAME.equals(this.boxName)) {
                throw DcCoreException.Misc.METHOD_NOT_ALLOWED;
            }

            // Boxを作成するためにCellCtlResource、ODataEntityResource(ODataProducer)が必要
            // この時点では "X-Dc-Credential" ヘッダーは不要なのでnullを指定する
            CellCtlResource cellctl = new CellCtlResource(this.accessContext, null, this.cellRsCmp);
            String keyName = "'" + this.boxName + "'";
            ODataEntityResource odataEntity = new ODataEntityResource(cellctl, Box.EDM_TYPE_NAME, keyName);

            Map<String, String> headers = new HashMap<String, String>();
            headers.put(HttpHeaders.CONTENT_TYPE, contentType);
            headers.put(HttpHeaders.CONTENT_LENGTH, contentLength);

            // X-Dc-RequestKeyの解析（指定なしの場合にデフォルト値を補充）
            BarFileInstaller installer =
                    new BarFileInstaller(this.cell, this.boxName, odataEntity, uriInfo);

            res = installer.barFileInstall(headers, inStream, event.getRequestKey());
            event.setResult(Integer.toString(res.getStatus()));
        } catch (RuntimeException e) {
            // TODO 内部イベントの正式対応が必要
            if (e instanceof DcCoreException) {
                event.setResult(Integer.toString(((DcCoreException) e).getStatus()));
                if (((DcCoreException) e).getStatus() > HttpStatus.SC_INTERNAL_SERVER_ERROR) {
                    event.setLevel(LEVEL.WARN);
                } else {
                    event.setLevel(LEVEL.ERROR);
                }
            } else {
                event.setResult(Integer.toString(HttpStatus.SC_INTERNAL_SERVER_ERROR));
                event.setLevel(LEVEL.ERROR);
            }
            throw e;
        } finally {
            // 終了ログファイル出力
            eventBus.outputEventLog(event);
        }
        return res;
    }
}
