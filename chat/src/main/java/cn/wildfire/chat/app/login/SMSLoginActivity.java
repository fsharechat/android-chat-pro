package cn.wildfire.chat.app.login;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.CountDownTimer;
import android.text.Editable;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.afollestad.materialdialogs.MaterialDialog;

import java.util.HashMap;
import java.util.Map;

import butterknife.Bind;
import butterknife.OnClick;
import butterknife.OnTextChanged;
import cn.wildfire.chat.app.Config;
import cn.wildfire.chat.app.login.model.LoginResult;
import cn.wildfire.chat.app.main.MainActivity;
import cn.wildfire.chat.kit.ChatManagerHolder;
import cn.wildfire.chat.kit.WfcBaseActivity;
import cn.wildfire.chat.kit.net.OKHttpHelper;
import cn.wildfire.chat.kit.net.SimpleCallback;
import cn.wildfire.chat.kit.net.base.StatusResult;
import cn.wildfirechat.chat.R;

public class SMSLoginActivity extends WfcBaseActivity {
    @Bind(R.id.loginButton)
    Button loginButton;
    @Bind(R.id.phoneNumberEditText)
    EditText phoneNumberEditText;
    @Bind(R.id.authCodeEditText)
    EditText authCodeEditText;
    @Bind(R.id.requestAuthCodeButton)
    Button requestAuthCodeButton;

    private String phoneNumber;
    private CountDownTimer authCodeCountDownTimer;

    @Override
    protected int contentLayout() {
        return R.layout.login_activity_sms;
    }

    private boolean isAccountValid() {
        String account = phoneNumberEditText.getText().toString().trim();
        return account.length() == 11 || account.contains("@");
    }

    private void updateButtonState() {
        // 倒计时期间发送按钮保持禁用，由 onFinish 恢复
        if (authCodeCountDownTimer == null) {
            requestAuthCodeButton.setEnabled(isAccountValid());
        }
        String authCode = authCodeEditText.getText().toString().trim();
        loginButton.setEnabled(isAccountValid() && !TextUtils.isEmpty(authCode));
    }

    @OnTextChanged(value = R.id.phoneNumberEditText, callback = OnTextChanged.Callback.AFTER_TEXT_CHANGED)
    void inputPhoneNumber(Editable editable) {
        updateButtonState();
    }

    @OnTextChanged(value = R.id.authCodeEditText, callback = OnTextChanged.Callback.AFTER_TEXT_CHANGED)
    void inputAuthCode(Editable editable) {
        updateButtonState();
    }

    @Override
    protected boolean showHomeMenuItem() {
        return false;
    }

    @OnClick(R.id.loginButton)
    void login() {
        String phoneNumber = phoneNumberEditText.getText().toString().trim();
        String authCode = authCodeEditText.getText().toString().trim();

        String url = "https://" + Config.APP_SERVER_HOST + ":" + Config.APP_SERVER_PORT + "/login";
        Map<String, String> params = new HashMap<>();
        params.put("mobile", phoneNumber);
        params.put("code", authCode);
        try {
            params.put("clientId", ChatManagerHolder.gChatManager.getClientId());
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(SMSLoginActivity.this, "网络出来问题了。。。", Toast.LENGTH_SHORT).show();
            return;
        }

        MaterialDialog dialog = new MaterialDialog.Builder(this)
                .content("登录中...")
                .progress(true, 100)
                .cancelable(false)
                .build();
        dialog.show();
        OKHttpHelper.post(url, params, new SimpleCallback<LoginResult>() {
            @Override
            public void onUiSuccess(LoginResult loginResult) {
                if (isFinishing()) {
                    return;
                }
                dialog.dismiss();
                ChatManagerHolder.gChatManager.connect(loginResult.getUserId(), loginResult.getToken());
                SharedPreferences sp = getSharedPreferences("config", Context.MODE_PRIVATE);
                sp.edit()
                        .putString("id", loginResult.getUserId())
                        .putString("token", loginResult.getToken())
                        .apply();
                Intent intent = new Intent(SMSLoginActivity.this, MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            }

            @Override
            public void onUiFailure(int code, String msg) {
                if (isFinishing()) {
                    return;
                }
                Toast.makeText(SMSLoginActivity.this, "登录失败：" + code + " " + msg, Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            }
        });
    }

    @OnClick(R.id.requestAuthCodeButton)
    void requestAuthCode() {
        // 请求期间先禁用防止重复点击，接口成功才进入倒计时，失败则立即恢复
        requestAuthCodeButton.setEnabled(false);

        Toast.makeText(this, "请求验证码...", Toast.LENGTH_SHORT).show();
        String phoneNumber = phoneNumberEditText.getText().toString().trim();
        String url = "https://" + Config.APP_SERVER_HOST + ":" + Config.APP_SERVER_PORT + "/send_code";
        Map<String, String> params = new HashMap<>();
        params.put("mobile", phoneNumber);
        OKHttpHelper.post(url, params, new SimpleCallback<StatusResult>() {
            @Override
            public void onUiSuccess(StatusResult statusResult) {
                if (isFinishing()) {
                    return;
                }
                if (statusResult.getCode() == 0) {
                    Toast.makeText(SMSLoginActivity.this, "发送验证码成功", Toast.LENGTH_SHORT).show();
                    startAuthCodeCountDown();
                } else {
                    Toast.makeText(SMSLoginActivity.this, "发送验证码失败: " + statusResult.getCode(), Toast.LENGTH_SHORT).show();
                    updateButtonState();
                }
            }

            @Override
            public void onUiFailure(int code, String msg) {
                if (isFinishing()) {
                    return;
                }
                Toast.makeText(SMSLoginActivity.this, "发送验证码失败: " + msg, Toast.LENGTH_SHORT).show();
                updateButtonState();
            }
        });
    }

    private void startAuthCodeCountDown() {
        authCodeCountDownTimer = new CountDownTimer(60 * 1000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                requestAuthCodeButton.setText((millisUntilFinished / 1000) + "s后重发");
            }

            @Override
            public void onFinish() {
                authCodeCountDownTimer = null;
                requestAuthCodeButton.setText("发送验证码");
                updateButtonState();
            }
        };
        authCodeCountDownTimer.start();
    }

    @Override
    protected void onDestroy() {
        if (authCodeCountDownTimer != null) {
            authCodeCountDownTimer.cancel();
            authCodeCountDownTimer = null;
        }
        super.onDestroy();
    }
}
