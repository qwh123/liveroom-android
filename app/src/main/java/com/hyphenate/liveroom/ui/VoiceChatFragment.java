package com.hyphenate.liveroom.ui;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.hyphenate.EMConferenceListener;
import com.hyphenate.EMValueCallBack;
import com.hyphenate.chat.EMClient;
import com.hyphenate.chat.EMConference;
import com.hyphenate.chat.EMConferenceManager;
import com.hyphenate.chat.EMConferenceMember;
import com.hyphenate.chat.EMConferenceStream;
import com.hyphenate.chat.EMStreamParam;
import com.hyphenate.chat.EMStreamStatistics;
import com.hyphenate.liveroom.Constant;
import com.hyphenate.liveroom.R;
import com.hyphenate.liveroom.entities.RoomType;
import com.hyphenate.liveroom.manager.PreferenceManager;
import com.hyphenate.liveroom.utils.DimensUtil;
import com.hyphenate.liveroom.widgets.IBorderView;
import com.hyphenate.liveroom.widgets.StateTextButton;
import com.hyphenate.liveroom.widgets.TalkerView;
import com.hyphenate.util.EMLog;

import java.util.Arrays;
import java.util.List;

/**
 * Created by zhangsong on 19-4-10
 */
public class VoiceChatFragment extends BaseFragment {
    private static final String TAG = "VoiceChatFragment";

    public static final int EVENT_TOBE_AUDIENCE = 1;
    public static final int EVENT_ROOM_TYPE_CHANGED = 2;

    public static final int RESULT_NO_HANDLED = 0;
    public static final int RESULT_NO_POSITION = 1;
    public static final int RESULT_ALREADY_TALKER = 2;

    private static final int BUTTON_MIC = 0;
    private static final int BUTTON_DISCONN = 1;
    private static final int BUTTON_TALK = 2;

    private static final int MAX_TALKERS = 6;

    private LinearLayout memberContainer;

    // private boolean isCreator;
    private String confId;
    private String password;

    private EMConferenceManager.EMConferenceRole conferenceRole;
    private EMStreamParam normalParam;
    private AudioManager audioManager;

    // Pair<username, talker view>
    private Pair<String, TalkerView>[] talkerViewList = new Pair[MAX_TALKERS];
    private String publishId = null;
    // 模式
    private RoomType roomType;
    private String currentUsername;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EMClient.getInstance().conferenceManager().addConferenceListener(conferenceListener);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.layout_member_container, container, false);
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        confId = getArguments().getString(Constant.EXTRA_CONFERENCE_ID);
        password = getArguments().getString(Constant.EXTRA_PASSWORD);
        currentUsername = PreferenceManager.getInstance().getCurrentUsername();

        memberContainer = getView().findViewById(R.id.container_member);

        audioManager = (AudioManager) getActivity().getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

        for (int i = 0; i < MAX_TALKERS; i++) {
            TalkerView talkerView = TalkerView.create(getContext())
                    .setName("已下线")
                    .canTalk(false);
            addMemberView(talkerView);
            talkerViewList[i] = new Pair<>(null, talkerView);
        }

        normalParam = new EMStreamParam();
        normalParam.setStreamType(EMConferenceStream.StreamType.NORMAL);
        normalParam.setVideoOff(true);
        normalParam.setAudioOff(false);

        EMClient.getInstance().conferenceManager().joinConference(confId, password, new EMValueCallBack<EMConference>() {
            @Override
            public void onSuccess(final EMConference value) {
                conferenceRole = value.getConferenceRole();
                EMLog.e(TAG, "join conference success, role: " + conferenceRole);
                if (conferenceRole == EMConferenceManager.EMConferenceRole.Admin) { // 管理员加入会议,默认publish 语音流.
                    // set channel attributes.
                    EMClient.getInstance().conferenceManager().setConferenceAttribute(Constant.PROPERTY_ADMIN, currentUsername, null);
                    roomType = RoomType.from(PreferenceManager.getInstance().getRoomType());
                    EMClient.getInstance().conferenceManager().setConferenceAttribute(Constant.PROPERTY_TYPE, roomType.getId(), null);
                    if (roomType == RoomType.HOST) {
                        EMClient.getInstance().conferenceManager().setConferenceAttribute(Constant.PROPERTY_TALKER, currentUsername, null);
                    }

                    // 不管任何模式,管理员加入默认开始推流且不静音
                    publish(false);

                    // 管理员设置自己的TalkerView
                    runOnUiThread(() -> {
                        TalkerView talkerView = updatePositionValue(0, currentUsername);
                        talkerView.setName(currentUsername)
                                .canTalk(true)
                                .setKing(true)
                                .setBorder(IBorderView.Border.GRAY)
                                .addButton(createButton(talkerView, BUTTON_MIC, IBorderView.Border.GREEN));

                        if (roomType == RoomType.HOST) {
                            talkerView.addButton(createButton(talkerView, BUTTON_TALK, IBorderView.Border.GREEN));
                        }
                    });
                }
            }

            @Override
            public void onError(final int error, final String errorMsg) {
                EMLog.e(TAG, "join conference failed error " + error + ", msg " + errorMsg);
                getActivity().setResult(error);
                getActivity().finish();
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        audioManager.setMode(AudioManager.MODE_NORMAL);
        closeSpeaker();

        EMClient.getInstance().conferenceManager().removeConferenceListener(conferenceListener);

        if (conferenceRole == EMConferenceManager.EMConferenceRole.Admin) { // 管理员退出时销毁会议
            EMClient.getInstance().conferenceManager().destroyConference(new EMValueCallBack() {
                @Override
                public void onSuccess(Object value) {
                    EMLog.i(TAG, "destroyConference success");
                }

                @Override
                public void onError(int error, String errorMsg) {
                    EMLog.e(TAG, "destroyConference failed " + error + ", " + errorMsg);
                }
            });
        } else {
            EMClient.getInstance().conferenceManager().exitConference(new EMValueCallBack() {
                @Override
                public void onSuccess(Object value) {
                    Log.i(TAG, "exitConference success");
                }

                @Override
                public void onError(int error, String errorMsg) {
                    EMLog.e(TAG, "exit conference failed " + error + ", " + errorMsg);
                }
            });
        }
    }

    public int handleTalkerRequest() {
        int p = findEmptyPosition();
        if (p == -1) {
            Log.i(TAG, "No position left.");
            return RESULT_NO_POSITION;
        }

        if (conferenceRole == EMConferenceManager.EMConferenceRole.Talker) {
            Log.i(TAG, "Current role is talker, publish directly.");
            publish(roomType == RoomType.HOST);

            TalkerView talkerView = updatePositionValue(p, currentUsername);
            talkerView.setName(currentUsername)
                    .canTalk(true)
                    .setBorder(IBorderView.Border.GRAY)
                    .addButton(createButton(talkerView, BUTTON_MIC, IBorderView.Border.GREEN))
                    .addButton(createButton(talkerView, BUTTON_DISCONN, IBorderView.Border.RED));

            return RESULT_ALREADY_TALKER;
        }

        return RESULT_NO_HANDLED;
    }

    private void publish(boolean pauseVoice) {
        EMClient.getInstance().conferenceManager().publish(normalParam, new EMValueCallBack<String>() {
            @Override
            public void onSuccess(String value) {
                publishId = value;
                // 主持模式下,新晋主播默认闭麦
                if (pauseVoice) {
                    EMClient.getInstance().conferenceManager().closeVoiceTransfer();
                }
            }

            @Override
            public void onError(int error, String errorMsg) {
                EMLog.e(TAG, "publish failed: error=" + error + ", msg=" + errorMsg);
            }
        });
    }

    /**
     * 停止推自己的数据
     */
    private void unpublish(final String publishId) {
        if (TextUtils.isEmpty(publishId)) {
            return;
        }

        EMClient.getInstance().conferenceManager().unpublish(publishId, new EMValueCallBack<String>() {
            @Override
            public void onSuccess(String value) {
                int existPosition = findExistPosition(currentUsername);
                runOnUiThread(() -> {
                    updatePositionValue(existPosition, null)
                            .setName("已下线")
                            .clearButtons()
                            .canTalk(false)
                            .setBorder(IBorderView.Border.NONE);
                });
            }

            @Override
            public void onError(int error, String errorMsg) {
                EMLog.e(TAG, "unpublish failed: error=" + error + ", msg=" + errorMsg);
            }
        });
    }

    private void openSpeaker() {
        if (!audioManager.isSpeakerphoneOn()) {
            audioManager.setSpeakerphoneOn(true);
        }
    }

    private void closeSpeaker() {
        if (audioManager.isSpeakerphoneOn()) {
            audioManager.setSpeakerphoneOn(false);
        }
    }

    private void addMemberView(TalkerView memberView) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int margin = DimensUtil.dp2px(getContext(), 1);
        params.topMargin = margin;
        params.bottomMargin = margin;
        memberContainer.addView(memberView, params);
    }

    /**
     * 订阅指定成员 stream
     */
    private void subscribe(EMConferenceStream stream) {
        EMClient.getInstance().conferenceManager().subscribe(stream, null, new EMValueCallBack<String>() {
            @Override
            public void onSuccess(String value) {
                Log.i(TAG, "Subscribe stream success");
            }

            @Override
            public void onError(int error, String errorMsg) {
                Log.e(TAG, "Subscribe stream failed: " + error + " - " + errorMsg);
            }
        });
    }

    // 找一个未被使用的位置
    private int findEmptyPosition() {
        for (int i = 0; i < MAX_TALKERS; ++i) {
            if (talkerViewList[i].first == null) {
                return i;
            }
        }
        return -1;
    }

    private int findExistPosition(String key) {
        for (int i = 0; i < MAX_TALKERS; ++i) {
            if (key == null && talkerViewList[i].first == null) {
                return i;
            }
            if (key != null && key.equals(talkerViewList[i].first)) {
                return i;
            }
        }
        return -1;
    }

    private TalkerView updatePositionValue(int position, String targetKey) {
        TalkerView talkerView = talkerViewList[position].second;
        talkerViewList[position] = new Pair<>(targetKey, talkerView);
        return talkerView;
    }

    private StateTextButton createButton(TalkerView v, int id, IBorderView.Border border) {
        if (id == BUTTON_MIC) {
            String[] titles = new String[]{"打开麦克风", "关闭麦克风"};
            return v.createButton(getContext(), BUTTON_MIC,
                    border != IBorderView.Border.GRAY ? titles[1] : titles[0], border,
                    (view, button) -> {
                        if (button.getBorder() == IBorderView.Border.GRAY) {
                            button.setBorder(IBorderView.Border.GREEN).setText(titles[1]);
                            EMClient.getInstance().conferenceManager().openVoiceTransfer();
                        } else {
                            button.setBorder(IBorderView.Border.GRAY).setText(titles[0]);
                            EMClient.getInstance().conferenceManager().closeVoiceTransfer();
                        }
                    });
        }
        if (id == BUTTON_DISCONN) {
            return v.createButton(getContext(), BUTTON_DISCONN,
                    "下线", border, (view, button) -> {
                        // 发送下线申请给管理员
                        if (onEventCallback != null) {
                            onEventCallback.onEvent(EVENT_TOBE_AUDIENCE, view.getName());
                        }
                    });
        }
        if (id == BUTTON_TALK) {
            return v.createButton(getContext(), BUTTON_TALK,
                    "发言", border, (view, button) -> {
                        // 设置频道属性
                        EMClient.getInstance().conferenceManager().setConferenceAttribute(
                                Constant.PROPERTY_TALKER, view.getName(), null);
                    });
        }

        Log.e(TAG, "createButton, unknown button type: " + id);
        return null;
    }

    private void runOnUiThread(Runnable r) {
        Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(r);
        }
    }

    private EMConferenceListener conferenceListener = new EMConferenceListener() {
        @Override
        public void onMemberJoined(EMConferenceMember member) {
            Log.i(TAG, "onMemberJoined: " + member.toString());
        }

        @Override
        public void onMemberExited(EMConferenceMember member) {
            Log.i(TAG, "onMemberExited: ");
        }

        @Override
        public void onStreamAdded(final EMConferenceStream stream) {
            Log.i(TAG, "onStreamAdded: ");
            subscribe(stream);

            // 更新名称已存在的TalkerView, 主要包含admin的TalkerView
            int existPosition = findExistPosition(stream.getUsername());

            TalkerView talkerView;
            if (existPosition != -1) { // 创建Admin talker view。
                talkerView = talkerViewList[existPosition].second;
            } else {
                // 寻找空位置放置其他主播
                int emptyPosition = findEmptyPosition();
                if (emptyPosition == -1) {
                    Log.i(TAG, "No position left.");
                    return;
                }
                talkerView = updatePositionValue(emptyPosition, stream.getUsername());
            }

            if (talkerView == null) {
                Log.i(TAG, "onStreamAdded, target talkerView is null.");
                return;
            }

            runOnUiThread(() -> {
                talkerView.setName(stream.getUsername())
                        .canTalk(!stream.isAudioOff());
                if (existPosition != -1) {
                    talkerView.setKing(true);
                }
                if (conferenceRole == EMConferenceManager.EMConferenceRole.Admin) {
                    if (roomType == RoomType.HOST) { // 主持模式下,管理员视角其他主播view中都有一个发言的按钮
                        talkerView.addButton(createButton(talkerView, BUTTON_TALK, IBorderView.Border.GREEN));
                    }
                    talkerView.addButton(createButton(talkerView, BUTTON_DISCONN, IBorderView.Border.RED));
                }
            });
        }

        @Override
        public void onStreamRemoved(EMConferenceStream stream) {
            Log.i(TAG, "onStreamRemoved: ");

            String username = stream.getUsername();
            int index = findExistPosition(username);
            runOnUiThread(() -> {
                updatePositionValue(index, null)
                        .setName("已下线")
                        .clearButtons()
                        .canTalk(false)
                        .setBorder(IBorderView.Border.NONE);
            });
        }

        @Override
        public void onStreamUpdate(EMConferenceStream stream) {
            Log.i(TAG, "onStreamUpdate: ");

            String username = stream.getUsername();
            runOnUiThread(() -> {
                int position = findExistPosition(username);
                if (position != -1) {
                    talkerViewList[position].second.canTalk(!stream.isAudioOff());
                }
            });
        }

        @Override
        public void onPassiveLeave(int i, String s) {
            Log.i(TAG, "onPassiveLeave: ");
            getActivity().finish();
        }

        @Override
        public void onConferenceState(ConferenceState conferenceState) {
        }

        @Override
        public void onStreamStatistics(EMStreamStatistics emStreamStatistics) {
        }

        @Override
        public void onStreamSetup(String s) {
        }

        @Override
        public void onSpeakers(List<String> list) {
            Log.i(TAG, "onSpeakers: " + Arrays.toString(list.toArray()));
            runOnUiThread(() -> {
                for (Pair<String, TalkerView> pair : talkerViewList) {
                    if (list.contains(pair.first)) {
                        pair.second.setTalking(true);
                    } else {
                        pair.second.setTalking(false);
                    }
                }
            });
        }

        @Override
        public void onReceiveInvite(String s, String s1, String s2) {
        }

        @Override
        public void onRoleChanged(EMConferenceManager.EMConferenceRole role) {
            conferenceRole = role;
            Log.i(TAG, "onRoleChanged: " + conferenceRole);

            if (role == EMConferenceManager.EMConferenceRole.Talker) { // 观众变成了主播
                int position = findEmptyPosition();
                if (position == -1) {
                    Log.i(TAG, "No position left.");
                    return;
                }

                publish(roomType == RoomType.HOST);

                runOnUiThread(() -> {
                    TalkerView talkerView = updatePositionValue(position, currentUsername);
                    talkerView.setName(currentUsername)
                            .setBorder(IBorderView.Border.GRAY)
                            .addButton(createButton(talkerView, BUTTON_DISCONN, IBorderView.Border.RED));

                    if (roomType == RoomType.HOST) {
                        talkerView.canTalk(false);
                    } else {
                        talkerView.canTalk(true)
                                .addButton(createButton(talkerView, BUTTON_MIC, IBorderView.Border.GREEN));
                    }
                });

            } else { // 主播变成了观众
                unpublish(publishId);
            }
        }

        @Override
        public void onAttributeUpdated(EMAttributeAction action, String key, String value) {
            Log.i(TAG, "onAttributeUpdated: " + action + " - " + key + " - " + value);
            // 把admin的名字绑定到第一个TalkerView
            if (Constant.PROPERTY_ADMIN.equals(key)) {
                updatePositionValue(0, value);
            }
            // 第一次加入房间时会获取到当前语聊房间的互动模式
            if (Constant.PROPERTY_TYPE.equals(key)) {
                roomType = RoomType.from(value);
                if (onEventCallback != null) {
                    onEventCallback.onEvent(EVENT_ROOM_TYPE_CHANGED, roomType);
                }
            }

            if (Constant.PROPERTY_TALKER.equals(key) && action == EMAttributeAction.UPDATE) {
                if (conferenceRole == EMConferenceManager.EMConferenceRole.Audience) {
                    return;
                }

                if (currentUsername.equals(value)) {
                    EMClient.getInstance().conferenceManager().openVoiceTransfer();
                    // 更新自己canTalk的状态
                    int p = findExistPosition(currentUsername);
                    if (p != -1) {
                        runOnUiThread(() -> talkerViewList[p].second.canTalk(true));
                    }
                } else {
                    EMClient.getInstance().conferenceManager().closeVoiceTransfer();
                    // 更新自己canTalk的状态
                    int p = findExistPosition(currentUsername);
                    if (p != -1) {
                        runOnUiThread(() -> talkerViewList[p].second.canTalk(false));
                    }
                }
            }
        }
    };
}
