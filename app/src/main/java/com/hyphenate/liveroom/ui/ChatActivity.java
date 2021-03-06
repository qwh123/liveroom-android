package com.hyphenate.liveroom.ui;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.hyphenate.EMCallBack;
import com.hyphenate.EMMessageListener;
import com.hyphenate.EMValueCallBack;
import com.hyphenate.chat.EMClient;
import com.hyphenate.chat.EMCmdMessageBody;
import com.hyphenate.chat.EMConferenceManager;
import com.hyphenate.chat.EMConferenceMember;
import com.hyphenate.chat.EMMessage;
import com.hyphenate.liveroom.Constant;
import com.hyphenate.liveroom.R;
import com.hyphenate.liveroom.entities.ChatRoom;
import com.hyphenate.liveroom.entities.RoomType;
import com.hyphenate.liveroom.manager.HttpRequestManager;
import com.hyphenate.liveroom.manager.PreferenceManager;
import com.hyphenate.liveroom.utils.AnimationUtil;
import com.hyphenate.liveroom.utils.OnMultiClickListener;
import com.hyphenate.liveroom.widgets.EaseTipDialog;
import com.hyphenate.util.EasyUtils;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.hyphenate.liveroom.Constant.EXTRA_PASSWORD;


public class ChatActivity extends BaseActivity {
    private static final String TAG = "ChatActivity";

    private static final int STATE_AUDIENCE = 0;
    private static final int STATE_DURING_REQUEST = 1;
    private static final int STATE_TALKER = 2;
    // voice conference join success.
    private static final int STATE_VOICE_JOIN = 0x1;
    // text chat room join success.
    private static final int STATE_TEXT_JOIN = 0x2;
    // voice conference and text chat room join success.
    private static final int STATE_ALL_JOIN = STATE_VOICE_JOIN | STATE_TEXT_JOIN;

    private String ownerName;
    private String roomName;
    private String textRoomId;
    private boolean isCreator;
    private boolean isAllowRequest;

    private int joinState = 0;

    private ChatRoom chatRoom;
    private RoomType roomType = RoomType.COMMUNICATION;

    private Button audioMixingButton;
    // 点赞或者礼物图片显示占位符
    private ImageView placeholder;
    private TextView roomTypeView;
    private TextView roomTypeDescView;
    private ImageView tobeTalkerView;

    private VoiceChatFragment voiceChatFragment;
    private TextChatFragment textChatFragment;

    private ConcurrentLinkedQueue<String> mTobeSpeakerRequest = new ConcurrentLinkedQueue<>();

    public static class Builder {
        private Intent intent;

        public Builder(Activity original) {
            intent = new Intent(original, ChatActivity.class);
            intent.putExtra(Constant.EXTRA_CHAT_TYPE, Constant.CHATTYPE_CHATROOM);
        }

        public Builder setPassword(String password) {
            intent.putExtra(Constant.EXTRA_PASSWORD, password);
            return this;
        }

        public Builder setChatRoomEntity(ChatRoom chatRoom) {
            intent.putExtra(Constant.EXTRA_CHAT_ROOM, chatRoom);
            return this;
        }

        public Builder setRoomType(String type) {
            intent.putExtra(Constant.EXTRA_ROOM_TYPE, type);
            return this;
        }

        public Intent build() {
            return intent;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_chat);

        chatRoom = (ChatRoom) getIntent().getSerializableExtra(Constant.EXTRA_CHAT_ROOM);
        ownerName = chatRoom.getOwnerName();
        isCreator = PreferenceManager.getInstance().getCurrentUsername().equalsIgnoreCase(ownerName);
        roomName = chatRoom.getRoomName();
        textRoomId = chatRoom.getRoomId();
        isAllowRequest = chatRoom.isAllowAudienceTalk();

        audioMixingButton = findViewById(R.id.btn_music);
        placeholder = findViewById(R.id.placeholder);
        roomTypeView = findViewById(R.id.tv_room_type);
        roomTypeDescView = findViewById(R.id.tv_type_desc);
        TextView roomNameView = findViewById(R.id.txt_room_name);
        TextView accountView = findViewById(R.id.txt_account);
        tobeTalkerView = findViewById(R.id.iv_request_tobe_talker);

        roomType = RoomType.from(getIntent().getStringExtra(Constant.EXTRA_ROOM_TYPE));
        roomTypeView.setText(roomType.getName());
        roomTypeDescView.setText(roomType.getDesc());

        if (!isCreator) {
            if (isAllowRequest) {
                updateTobeTalkerView(STATE_AUDIENCE);
            }
            tobeTalkerView.setOnClickListener(new OnMultiClickListener() {
                @Override
                public void onMultiClick(View v) {
                    if (!isAllowRequest) {
                        Toast.makeText(getApplicationContext(), "该语聊房间不允许申请连麦 ~", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    int result = voiceChatFragment.handleTalkerRequest();
                    if (result == VoiceChatFragment.RESULT_NO_POSITION) {
                        new EaseTipDialog.Builder(ChatActivity.this)
                                .setStyle(EaseTipDialog.TipDialogStyle.INFO)
                                .setTitle("提示")
                                .setMessage("该聊天室主播人数已满,请稍后重试 ...")
                                .addButton("确定", Constant.COLOR_BLACK, Constant.COLOR_WHITE, (dialog, view) -> {
                                    dialog.dismiss();
                                })
                                .build()
                                .show();
                    } else if (result == VoiceChatFragment.RESULT_ALREADY_TALKER) {
                        updateTobeTalkerView(STATE_TALKER);
                    } else if (result == VoiceChatFragment.RESULT_NO_HANDLED) {
                        updateTobeTalkerView(STATE_DURING_REQUEST);
                        // 发送上麦申请
                        sendRequest(ownerName, Constant.OP_REQUEST_TOBE_SPEAKER, voiceChatFragment.getStreamId());
                    }
                }
            });
        } else { // 管理员视角显示伴音按钮
            audioMixingButton.setVisibility(View.VISIBLE);
        }

        roomNameView.setText(roomName);
        accountView.setText(textRoomId);

        textChatFragment = new TextChatFragment();
        textChatFragment.setArguments(getIntent().getExtras());
        textChatFragment.setOnEventCallback((op, args) -> {
            Log.i(TAG, "OnEventCallback: " + op);
            if (TextChatFragment.MSG_FAVOURITE == op || TextChatFragment.MSG_GIFT == op) {
                runOnUiThread(() -> {
                    placeholder.setVisibility(View.VISIBLE);
                    AnimationSet set = AnimationUtil.create();
                    set.setAnimationListener(new Animation.AnimationListener() {
                        @Override
                        public void onAnimationStart(Animation animation) {
                        }

                        @Override
                        public void onAnimationEnd(Animation animation) {
                            placeholder.setVisibility(View.GONE);
                            placeholder.setImageResource(0);
                        }

                        @Override
                        public void onAnimationRepeat(Animation animation) {
                        }
                    });
                    if (TextChatFragment.MSG_FAVOURITE == op) {
                        placeholder.setImageResource(R.drawable.em_ic_favorite);
                    } else {
                        placeholder.setImageResource(R.drawable.em_ic_giftcard);
                    }
                    placeholder.startAnimation(set);
                });
            } else if (TextChatFragment.EVENT_JOIN_SUCCESS == op) {
                joinState |= STATE_TEXT_JOIN;
                checkJoinState();
            }
        });
        getSupportFragmentManager().beginTransaction().add(R.id.container_chat, textChatFragment).commit();

        voiceChatFragment = new VoiceChatFragment();
        voiceChatFragment.setArguments(getIntent().getExtras());
        voiceChatFragment.setOnEventCallback((op, args) -> {
            if (VoiceChatFragment.EVENT_TOBE_AUDIENCE == op) {
                if (isCreator) { // 管理员下线其他主播
                    String username = (String) args[0];
                    grantRole(username, EMConferenceManager.EMConferenceRole.Audience);
                } else { // 主播向管理员发送下线的申请
                    sendRequest(ownerName, Constant.OP_REQUEST_TOBE_AUDIENCE, voiceChatFragment.getStreamId());
                }
            } else if (VoiceChatFragment.EVENT_ROOM_TYPE_CHANGED == op) {
                runOnUiThread(() -> {
                    roomType = (RoomType) args[0];
                    roomTypeView.setText(roomType.getName());
                    roomTypeDescView.setText(roomType.getDesc());
                });
            } else if (VoiceChatFragment.EVENT_BE_TALKER_SUCCESS == op) {
                runOnUiThread(() -> updateTobeTalkerView(STATE_TALKER));
            } else if (VoiceChatFragment.EVENT_BE_TALKER_FAILED == op) {
                runOnUiThread(() -> new EaseTipDialog.Builder(this)
                        .setStyle(EaseTipDialog.TipDialogStyle.INFO)
                        .setTitle("提示")
                        .setMessage("该聊天室主播人数已满,请稍后重试 ...")
                        .addButton("确定", Constant.COLOR_BLACK, Constant.COLOR_WHITE,
                                (dialog, view) -> dialog.dismiss())
                        .build()
                        .show());
            } else if (VoiceChatFragment.EVENT_BE_AUDIENCE_SUCCESS == op) {
                runOnUiThread(() -> updateTobeTalkerView(STATE_AUDIENCE));
            } else if (VoiceChatFragment.EVENT_PLAY_MUSIC_DEFAULT == op) { // 加入音视频会议成功后回调
                onClick(audioMixingButton);
            } else if (VoiceChatFragment.EVENT_OCCUPY_SUCCESS == op) {
                textChatFragment.sendTextMessage(String.format("[@%s] 抢麦成功", args[0]));
            } else if (op == TextChatFragment.EVENT_JOIN_SUCCESS) {
                joinState |= STATE_VOICE_JOIN;
                checkJoinState();
            }
        });
        getSupportFragmentManager().beginTransaction().add(R.id.container_member, voiceChatFragment).commit();

        EMClient.getInstance().chatManager().addMessageListener(messageListener);

    }

    @Override
    protected void onNewIntent(Intent intent) {
        String username = intent.getStringExtra(Constant.EXTRA_CHATROOM_ID);
        if (textRoomId.equals(username)) {
            super.onNewIntent(intent);
        } else {
            finish();
            startActivity(intent);
        }
    }

    @Override
    public void onBackPressed() {
        // hook back menu.
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mTobeSpeakerRequest.clear();
        EMClient.getInstance().chatManager().removeMessageListener(messageListener);
    }

    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_music:
                boolean isActived = audioMixingButton.isActivated();
                if (!isActived) {
                    // 设置频道属性,自己收到频道属性变化后设置伴音
                    EMClient.getInstance().conferenceManager().setConferenceAttribute(
                            Constant.PROPERTY_MUSIC, "/assets/audio.mp3", null);
                    textChatFragment.sendTextMessage("开始歌曲");
                } else {
                    // 设置频道属性,自己收到频道属性变化后设置伴音
                    EMClient.getInstance().conferenceManager().deleteConferenceAttribute(
                            Constant.PROPERTY_MUSIC, null);
                    textChatFragment.sendTextMessage("停止歌曲");
                }
                audioMixingButton.setActivated(!isActived);
                break;
            case R.id.btn_contacts:
                Intent i = new Intent(ChatActivity.this, MembersActivity.class);
                i.putExtra(Constant.EXTRA_CHATROOM_ID, textRoomId);
                startActivity(i);
                break;
            case R.id.btn_share:
                Intent intent = new Intent(ChatActivity.this, SharedActivity.class);
                intent.putExtra(Constant.EXTRA_ROOM_NAME, roomName);
                intent.putExtra(Constant.EXTRA_ROOM_ADMIN, ownerName);
                if (chatRoom != null) {
                    intent.putExtra(Constant.EXTRA_ROOM_PWD, chatRoom.getRtcConfrPassword());
                }
                startActivity(intent);
                break;
            case R.id.btn_detail:
                startActivity(new RoomDetailsActivity.Builder(ChatActivity.this)
                        .setChatRoomEntity(chatRoom)
                        .setPassword(getIntent().getStringExtra(EXTRA_PASSWORD))
                        .setRoomType(roomType.getId())
                        .build());
                break;
            case R.id.btn_exit:
                if (!isCreator) { // 非语聊室创建者退出
                    textChatFragment.sendTextMessage("我走了");
                    finish();
                    return;
                }

                // 请求app server,移除该聊天室item.
                HttpRequestManager.getInstance().deleteChatRoom(textRoomId, new HttpRequestManager.IRequestListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        // 会反应到VoiceChatFragment和TextChatFragment的onDestroy中.
                        finish();
                    }

                    @Override
                    public void onFailed(int errCode, String desc) {
                        Toast.makeText(ChatActivity.this, errCode + " - " + desc, Toast.LENGTH_SHORT).show();
                        // 会反应到VoiceChatFragment和TextChatFragment的onDestroy中.
                        finish();
                    }
                });
                break;
        }
    }

    public void updateTobeTalkerView(int state) {
        switch (state) {
            case STATE_AUDIENCE:
                tobeTalkerView.setImageResource(R.drawable.em_ic_tobe_talker);
                tobeTalkerView.setVisibility(View.VISIBLE);
                tobeTalkerView.setClickable(true);
                break;
            case STATE_DURING_REQUEST:
                tobeTalkerView.setImageResource(R.drawable.em_ic_during_tobe_talker);
                tobeTalkerView.setVisibility(View.VISIBLE);
                tobeTalkerView.setClickable(false);
                break;
            case STATE_TALKER:
                tobeTalkerView.setVisibility(View.GONE);
                break;
        }
    }

    private void sendRequest(String to, String op, String streamId) {
        EMMessage msg = EMMessage.createSendMessage(EMMessage.Type.CMD);
        EMCmdMessageBody cmdBody = new EMCmdMessageBody("");
        cmdBody.deliverOnlineOnly(true);
        msg.addBody(cmdBody);
        msg.setTo(to);
        msg.setAttribute(Constant.EM_CONFERENCE_OP, op);
        msg.setAttribute(Constant.EM_CONFERENCE_ID, streamId);
        msg.setMessageStatusCallback(new EMCallBack() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "onSuccess: ");
            }

            @Override
            public void onError(int i, String s) {
                Log.i(TAG, "onError: " + i + " - " + s);
            }

            @Override
            public void onProgress(int i, String s) {
                Log.i(TAG, "onProgress: " + i + " - " + s);
            }
        });
        EMClient.getInstance().chatManager().sendMessage(msg);
    }

    private void grantRole(String username, EMConferenceManager.EMConferenceRole targetRole) {
        String jid = EasyUtils.getMediaRequestUid(EMClient.getInstance().getOptions().getAppKey(),
                username);
        EMClient.getInstance().conferenceManager().grantRole(
                "", new EMConferenceMember(jid, "", ""),
                targetRole, new EMValueCallBack<String>() {
                    @Override
                    public void onSuccess(String s) {
                        Log.i(TAG, "grantRole onSuccess: " + s);
                        if (!textChatFragment.isInChatRoom(username)) {
                            Log.d(TAG, username + " is not the chatRoom member");
                            return;
                        }
                        if (targetRole == EMConferenceManager.EMConferenceRole.Talker) {
                            textChatFragment.sendTextMessage(String.format("[@%s] 上麦", username));
                        } else if (targetRole == EMConferenceManager.EMConferenceRole.Audience) {
                            textChatFragment.sendTextMessage(String.format("[@%s] 下麦", username));
                        }
                    }

                    @Override
                    public void onError(int i, String s) {
                        Log.i(TAG, "grantRole onError: " + i + " - " + s);
                        // 如果改变角色失败,通知申请方改变申请上麦按钮为可上麦.
                        if (targetRole == EMConferenceManager.EMConferenceRole.Talker) {
                            sendRequest(username, Constant.OP_REQUEST_BE_REJECTED, voiceChatFragment.getStreamId());
                        }
                    }
                }
        );
    }

    private void checkJoinState() {
        Log.i(TAG, "checkJoinState, current join state: " + joinState);
        if ((joinState & STATE_ALL_JOIN) == STATE_ALL_JOIN) {
            // 文字聊天室和语音会议都加入成功
            textChatFragment.sendTextMessage("我来了");
        }
    }

    private EMMessageListener messageListener = new EMMessageListener() {
        @Override
        public void onMessageReceived(List<EMMessage> list) {
        }

        @Override
        public void onCmdMessageReceived(List<EMMessage> list) {
            String currentStreamId = voiceChatFragment.getStreamId();
            for (EMMessage msg : list) {
                String streamId = msg.getStringAttribute(Constant.EM_CONFERENCE_ID, null);
                if (currentStreamId == null || !currentStreamId.equals(streamId)) {
                    continue;
                }

                String operation = msg.getStringAttribute(Constant.EM_CONFERENCE_OP, null);
                if (Constant.OP_REQUEST_TOBE_SPEAKER.equals(operation)) { // 收到观众上麦申请
                    boolean autoAgreeRequest = PreferenceManager.getInstance().isAutoAgree();
                    if (autoAgreeRequest) {
                        int emptyPosition = voiceChatFragment.findEmptyPosition();
                        if (emptyPosition == -1) {
                            // no empty position
                            sendRequest(msg.getFrom(), Constant.OP_REQUEST_BE_REJECTED, voiceChatFragment.getStreamId());
                        } else {
                            grantRole(msg.getFrom(), EMConferenceManager.EMConferenceRole.Talker);
                        }
                    } else {
                        mTobeSpeakerRequest.offer(msg.getFrom());
                        notifyShowDialog();
                    }
                } else if (Constant.OP_REQUEST_TOBE_AUDIENCE.equals(operation)) { // 收到主播下麦申请
                    grantRole(msg.getFrom(), EMConferenceManager.EMConferenceRole.Audience);
                } else if (Constant.OP_REQUEST_BE_REJECTED.equals(operation)) { // 观众上麦被拒绝
                    runOnUiThread(() -> {
                        updateTobeTalkerView(STATE_AUDIENCE);
                        new EaseTipDialog.Builder(ChatActivity.this)
                                .setStyle(EaseTipDialog.TipDialogStyle.INFO)
                                .setTitle("提示")
                                .setMessage("您的上麦申请,被管理员拒绝.")
                                .addButton("知道了", Constant.COLOR_BLACK, Constant.COLOR_WHITE,
                                        (dialog, v) -> dialog.dismiss())
                                .build()
                                .show();
                    });
                }
            }
        }

        @Override
        public void onMessageRead(List<EMMessage> list) {
        }

        @Override
        public void onMessageDelivered(List<EMMessage> list) {
        }

        @Override
        public void onMessageRecalled(List<EMMessage> list) {
        }

        @Override
        public void onMessageChanged(EMMessage emMessage, Object o) {
        }
    };

    private boolean showingRequestDialog;

    private void notifyShowDialog() {
        runOnUiThread(() -> {
            if (!mTobeSpeakerRequest.isEmpty() && !showingRequestDialog) {
                String msgFrom = mTobeSpeakerRequest.poll();
                if (msgFrom == null) {
                    return;
                }
                int emptyPosition = voiceChatFragment.findEmptyPosition();
                if (emptyPosition == -1) {
                    // no empty position
                    sendRequest(msgFrom, Constant.OP_REQUEST_BE_REJECTED, voiceChatFragment.getStreamId());
                    return;
                }
                showingRequestDialog = true;

                new AlertDialog.Builder(ChatActivity.this)
                        .setTitle("提示")
                        .setIcon(R.drawable.em_ic_tips)
                        .setMessage(msgFrom + " 申请上麦互动，同意吗？")
                        .setOnCancelListener(dialog -> {
                            sendRequest(msgFrom, Constant.OP_REQUEST_BE_REJECTED, voiceChatFragment.getStreamId());
                            showingRequestDialog = false;
                            notifyShowDialog();
                        })
                        .setPositiveButton("同意", (dialog, which) -> {
                            grantRole(msgFrom, EMConferenceManager.EMConferenceRole.Talker);
                            showingRequestDialog = false;
                            notifyShowDialog();
                        })
                        .setNegativeButton("拒绝", (dialog, which) -> {
                            sendRequest(msgFrom, Constant.OP_REQUEST_BE_REJECTED, voiceChatFragment.getStreamId());
                            showingRequestDialog = false;
                            notifyShowDialog();
                        }).show();
            }
//                            new EaseTipDialog.Builder(ChatActivity.this)
//                                    .setStyle(EaseTipDialog.TipDialogStyle.INFO)
//                                    .setCanceledOnTouchOutside(false)
//                                    .dismissCalcelButton(true)
//                                    .setTitle("提示")
//                                    .setMessage(msg.getFrom() + " 申请上麦互动，同意吗？")
//                                    .addButton("同意", Constant.COLOR_BLACK, Constant.COLOR_WHITE,
//                                            (dialog, v) -> {
//                                                dialog.dismiss();
//                                                grantRole(msg.getFrom(), EMConferenceManager.EMConferenceRole.Talker);
//                                            })
//                                    .addButton("拒绝", Constant.COLOR_BLACK, Constant.COLOR_WHITE,
//                                            (dialog, v) -> {
//                                                dialog.dismiss();
//                                                sendRequest(msg.getFrom(), Constant.OP_REQUEST_BE_REJECTED, voiceChatFragment.getStreamId());
//                                            })
//                                    .build()
//                                    .show();
        });
    }
}
