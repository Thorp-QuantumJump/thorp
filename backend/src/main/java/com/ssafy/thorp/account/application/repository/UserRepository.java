package com.ssafy.thorp.account.application.repository;
import com.ssafy.thorp.account.domain.model.User;

import java.util.Optional;
public interface UserRepository {
    User save(User user);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByPhoneNumber(String phoneNumber);

}
