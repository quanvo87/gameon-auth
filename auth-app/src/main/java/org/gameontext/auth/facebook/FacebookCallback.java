/*******************************************************************************
 * Copyright (c) 2015 IBM Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.gameontext.auth.facebook;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.gameontext.auth.JwtAuth;
import org.gameontext.auth.Log;

import com.restfb.DefaultFacebookClient;
import com.restfb.DefaultWebRequestor;
import com.restfb.FacebookClient;
import com.restfb.Parameter;
import com.restfb.Version;
import com.restfb.WebRequestor;
import com.restfb.exception.FacebookOAuthException;
import com.restfb.types.User;

@WebServlet("/FacebookCallback")
public class FacebookCallback extends JwtAuth {
    private static final long serialVersionUID = 1L;

    @Resource(lookup = "facebookAppID")
    private String appId;
    @Resource(lookup = "facebookSecret")
    private String secretKey;
    @Resource(lookup = "authCallbackURLSuccess")
    private String callbackSuccess;
    @Resource(lookup = "authCallbackURLFailure")
    private String callbackFailure;
    @Resource(lookup = "authURL")
    private String authURL;

    @PostConstruct
    private void verifyInit() {
        if (callbackSuccess == null) {
            System.err.println("Error finding webapp base URL; please set this in your environment variables!");
        }
    }

    /**
     * Utility method to obtain an accesstoken given a facebook code and the
     * redirecturl used to obtain it.
     *
     * @param code
     * @param redirectUrl
     * @return the acccess token
     * @throws IOException
     *             if anything goes wrong.
     */
    private FacebookClient.AccessToken getFacebookUserToken(String code, String redirectUrl) throws IOException {
        // restfb doesn't seem to have an obvious method to convert a response
        // code into an access token
        // but according to the spec, this is the easy way to do it.. we'll use
        // WebRequestor from restfb to
        // handle the request/response.

        WebRequestor wr = new DefaultWebRequestor();
        WebRequestor.Response accessTokenResponse = wr
                .executeGet("https://graph.facebook.com/oauth/access_token?client_id=" + appId + "&redirect_uri="
                        + redirectUrl + "&client_secret=" + secretKey + "&code=" + code);

        // finally, restfb can now process the reply to get us our access token.
        String queryString = getQueryString(accessTokenResponse.getBody());

        return DefaultFacebookClient.AccessToken.fromQueryString(queryString);
    }

    /**
     * Given a JSON String, returns a query String that can be used to make an AccessToken.
     *
     * @param accessTokenResponse A JSON String response from an auth request to Facebook.
     * @return A query string that can be used to make an AccessToken.
     */
    private String getQueryString(String accessTokenResponse) {
        JsonReader reader = Json.createReader(new StringReader(accessTokenResponse));
        JsonObject jsonObject = reader.readObject();

        String access_token = jsonObject.getString("access_token");
        String expires = jsonObject.getString("expires_in");

        return "access_token=" + access_token + "&expires=" + expires;
    }

    /**
     * Method that performs introspection on an AUTH string, and returns data as
     * a String->String hashmap.
     *
     * @param auth
     *            the authstring to query, as built by an auth impl.
     * @return the data from the introspect, in a map.
     * @throws IOException
     *             if anything goes wrong.
     */
    private Map<String, String> introspectAuth(String accesstoken) throws IOException {
        Map<String, String> results = new HashMap<String, String>();

        // create a fb client using the supplied access token
        FacebookClient client = new DefaultFacebookClient(accesstoken, Version.VERSION_2_5);

        try {
            // get back just the email, and name for the user, we'll get the id
            // for free.
            // fb only allows us to retrieve the things we asked for back in
            // FacebookAuth when creating the token.
            User userWithMetadata = client.fetchObject("me", User.class, Parameter.with("fields", "email,name"));

            results.put("valid", "true");
            results.put("email", userWithMetadata.getEmail());
            results.put("name", userWithMetadata.getName());
            results.put("id", "facebook:" + userWithMetadata.getId());

        } catch (FacebookOAuthException e) {
            results.clear();
            results.put("valid", "false");
        }

        return results;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        // facebook redirected to us, and there should be a code awaiting us as
        // part of the request.
        String code = request.getParameter("code");

        // need the redirect url for fb to give us a token from the code it
        // supplied.
        String callbackURL = authURL + "/FacebookCallback";

        Log.log(Level.FINEST, this, "Facebook token URL: {0}", callbackURL);

        // convert the code into an access token.
        FacebookClient.AccessToken token = getFacebookUserToken(code, callbackURL.toString());

        String accessToken = token.getAccessToken();

        Map<String, String> claims = introspectAuth(accessToken);

        // if auth key was no longer valid, we won't build a jwt. redirect back
        // to start.
        if (!"true".equals(claims.get("valid"))) {
            response.sendRedirect(callbackFailure);
        } else {
            String newJwt = createJwt(claims);

            // debug.
            System.out.println("New User Authed: " + claims.get("id"));
            response.sendRedirect(callbackSuccess + "/" + newJwt);
        }
    }
}
