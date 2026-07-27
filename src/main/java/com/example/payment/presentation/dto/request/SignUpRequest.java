package com.example.payment.presentation.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SignUpRequest {

    @NotBlank(message = "이메일을 입력하세요.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    private String email;

    // 상한을 두는 이유: BCrypt는 72바이트를 넘는 입력을 조용히 잘라낸다.
    @NotBlank(message = "비밀번호를 입력하세요.")
    @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하여야 합니다.")
    private String password;

    @Size(max = 50, message = "표시 이름은 50자를 넘을 수 없습니다.")
    private String displayName;
}
