package com.example.spatialphoto

import com.example.spatialphoto.domain.usecase.HeadboxUseCase
import org.junit.Assert.*
import org.junit.Test

class HeadboxUseCaseTest {
 @Test fun centerAccepted() { assertTrue(HeadboxUseCase.allowed(0f,0f,0f,0f,0f)) }
 @Test fun limitAccepted() { assertTrue(HeadboxUseCase.allowed(.1f,0f,0f,10f,8f)) }
 @Test fun outsideRejected() { assertFalse(HeadboxUseCase.allowed(.101f,0f,0f,0f,0f)) }
 @Test fun yawRejected() { assertFalse(HeadboxUseCase.allowed(0f,0f,0f,11f,0f)) }
 @Test fun invalidRejected() { assertFalse(HeadboxUseCase.allowed(Float.NaN,0f,0f,0f,0f)) }
}
