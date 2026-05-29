package com.sap.adt.patch;

import java.util.concurrent.CompletableFuture;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import com.sap.adt.destinations.model.IDestinationData;
import com.sap.adt.destinations.model.internal.AuthenticationToken;
import com.sap.adt.destinations.logon.IAdtLogonService;
import com.sap.adt.ls.client.AdtLanguageClientAccess;
import com.sap.adt.ls.internal.project.IAdtLsClientDestinationExtension;
import com.sap.adt.ls.internal.project.model.InputParam;
import com.sap.adt.ls.internal.project.model.UserInputParams;
import com.sap.adt.ls.internal.project.model.UserInputResponse;

/**
 * Injected helper that performs an RFC logon using a username/password
 * (basic auth) instead of SSO/SNC. The official extension's logon worker
 * throws UnsupportedOperationException for non-SSO RFC destinations; the
 * bytecode patch redirects that branch here.
 *
 * The credential machinery already exists in the communication layer:
 *   - JCoDestinationRegistry.register(dest, token) stores the token and
 *     feeds token.getPassword() to JCo at connect time.
 *   - dest.getUser() supplies the user.
 * We only need to prompt for the password, build the token, and call
 * ensureLoggedOn.
 */
public final class BasicAuthRfcLogon {
  public static IStatus logon(IDestinationData dest, IAdtLogonService logonService, IProgressMonitor monitor) {
    try {
      IAdtLsClientDestinationExtension client = (IAdtLsClientDestinationExtension)
          AdtLanguageClientAccess.get().getLanguageClientExtension(IAdtLsClientDestinationExtension.class);
      UserInputParams params = new UserInputParams(
          dest.getId(), "RFC Logon: " + dest.getId(),
          new InputParam(new InputParam.Field("password", ""), "Password", "Enter your ABAP password", true));
      CompletableFuture<UserInputResponse> f = client.requestLogonInput(params);
      UserInputResponse resp = f.get();
      if (resp == null || resp.fields() == null || resp.fields().length == 0) return Status.CANCEL_STATUS;
      AuthenticationToken token = new AuthenticationToken();
      token.setPassword(resp.fields()[0].value());
      return logonService.ensureLoggedOn(dest, token, monitor);
    } catch (Exception e) {
      return Status.error("RFC basic auth logon failed: " + e.getMessage());
    }
  }
}
