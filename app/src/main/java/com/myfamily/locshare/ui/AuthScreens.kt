package com.myfamily.locshare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myfamily.locshare.data.FamilyRepository

private val errorColor = Color(0xFFD32F2F)

/** 로그인 / 가입 화면 */
@Composable
fun AuthScreen(repo: FamilyRepository) {
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var signup by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("어디찌?", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("우리 가족 위치 공유", color = Color.Gray)
        Spacer(Modifier.height(28.dp))

        if (signup) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(8) },
                label = { Text("이름 (가족에게 보이는 이름)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("이메일") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pw,
            onValueChange = { pw = it },
            label = { Text("비밀번호 (6자 이상)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        err?.let {
            Text(it, color = errorColor, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = {
                if (email.isBlank() || pw.length < 6) {
                    err = "이메일과 6자 이상의 비밀번호를 입력해 주세요"
                } else if (signup && name.isBlank()) {
                    err = "이름을 입력해 주세요"
                } else {
                    busy = true
                    err = null
                    if (signup) {
                        repo.signUp(email, pw, name) { e ->
                            busy = false
                            err = e
                        }
                    } else {
                        repo.signIn(email, pw) { e ->
                            busy = false
                            err = e
                        }
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "잠시만요…" else if (signup) "가입하기" else "로그인") }

        TextButton(onClick = {
            signup = !signup
            err = null
        }) { Text(if (signup) "이미 계정이 있어요 (로그인)" else "처음이에요 (가입하기)") }
    }
}

/** 로그인은 했지만 아직 가족 서클이 없을 때: 새로 만들기 또는 코드로 참여 */
@Composable
fun CircleScreen(repo: FamilyRepository) {
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("가족 서클", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "서클에 들어간 사람끼리만 서로의 위치를 볼 수 있어요.\n" +
                "처음 한 명이 서클을 만들고, 나머지 가족은 그 서클의 코드로 들어와요.",
            color = Color.Gray,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                busy = true
                err = null
                repo.createCircle { e ->
                    busy = false
                    err = e
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("새 가족 서클 만들기") }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        Text("이미 서클이 있나요?", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(10) },
            label = { Text("서클 코드 (10자리)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                busy = true
                err = null
                repo.joinCircle(code) { e ->
                    busy = false
                    err = e
                }
            },
            enabled = !busy && (code.length == 6 || code.length == 10),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("코드로 참여") }

        err?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = errorColor, fontSize = 13.sp)
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { repo.signOut() }) { Text("로그아웃") }
    }
}
