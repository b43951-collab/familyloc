package com.myfamily.locshare.data

object FirebaseConfig {
    /**
     * Realtime Database 주소.
     * Firebase 콘솔 → Realtime Database 화면 맨 위에 보이는 https://... 주소와 같아야 합니다.
     *  - 싱가포르(asia-southeast1)로 만들었다면 아래 값 그대로
     *  - 미국(us-central1)이면 https://familyloc-f00ad-default-rtdb.firebaseio.com
     */
    const val DB_URL = "https://familyloc-f00ad-default-rtdb.asia-southeast1.firebasedatabase.app"
}
