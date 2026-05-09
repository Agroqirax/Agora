#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "LlamaChatEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct ChatHandle {
    llama_model * model   = nullptr;
    llama_context * ctx   = nullptr;
    const llama_vocab * vocab = nullptr;
    std::string path;
    int32_t n_ctx = 0;
    volatile bool cancelled = false;
};

static bool abort_callback(void * data) {
    ChatHandle * handle = (ChatHandle *)data;
    return handle->cancelled;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatLoadModel(
    JNIEnv * env, jclass /*clazz*/, jstring path, jint n_ctx) {

    const char * path_str = env->GetStringUTFChars(path, nullptr);
    if (!path_str) return 0;

    ChatHandle * handle = new ChatHandle();
    if (!handle) {
        env->ReleaseStringUTFChars(path, path_str);
        return 0;
    }

    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    handle->model = llama_model_load_from_file(path_str, model_params);
    env->ReleaseStringUTFChars(path, path_str);

    if (!handle->model) {
        LOGE("Failed to load model from file");
        delete handle;
        return 0;
    }

    handle->vocab = llama_model_get_vocab(handle->model);
    handle->n_ctx = n_ctx;

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.embeddings   = false;
    ctx_params.pooling_type = LLAMA_POOLING_TYPE_NONE;
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = std::min(n_ctx, 512);
    ctx_params.n_ubatch = std::min(n_ctx, 512);
    ctx_params.no_perf = false;

    handle->ctx = llama_init_from_model(handle->model, ctx_params);
    if (!handle->ctx) {
        LOGE("Failed to create context");
        llama_model_free(handle->model);
        delete handle;
        return 0;
    }

    llama_set_abort_callback(handle->ctx, abort_callback, handle);

    LOGD("Chat model loaded: n_ctx=%d, n_ctx_train=%d",
         n_ctx, llama_model_n_ctx_train(handle->model));

    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT jstring JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatGetTemplate(
    JNIEnv * env, jclass /*clazz*/, jlong handle_ptr) {

    if (!handle_ptr) return nullptr;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    if (!handle->model) return nullptr;

    const char * tmpl = llama_model_chat_template(handle->model, nullptr);
    if (!tmpl) return nullptr;
    return env->NewStringUTF(tmpl);
}

JNIEXPORT jstring JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatApplyTemplate(
    JNIEnv * env, jclass /*clazz*/, jlong handle_ptr,
    jobjectArray messages, jboolean add_ass) {

    if (!handle_ptr) return nullptr;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    if (!handle->model) return nullptr;

    jint n_msg = env->GetArrayLength(messages);

    std::vector<llama_chat_message> chat_msgs(n_msg);
    std::vector<std::string> role_storage(n_msg);
    std::vector<std::string> content_storage(n_msg);

    for (jint i = 0; i < n_msg; i++) {
        jobject msg = env->GetObjectArrayElement(messages, i);
        jclass msg_class = env->GetObjectClass(msg);

        jfieldID role_field = env->GetFieldID(msg_class, "role", "Ljava/lang/String;");
        jfieldID content_field = env->GetFieldID(msg_class, "content", "Ljava/lang/String;");

        jstring role_jstr = (jstring)env->GetObjectField(msg, role_field);
        jstring content_jstr = (jstring)env->GetObjectField(msg, content_field);

        const char * role_cstr = env->GetStringUTFChars(role_jstr, nullptr);
        const char * content_cstr = env->GetStringUTFChars(content_jstr, nullptr);

        role_storage[i] = std::string(role_cstr ? role_cstr : "user");
        content_storage[i] = std::string(content_cstr ? content_cstr : "");

        if (role_cstr) env->ReleaseStringUTFChars(role_jstr, role_cstr);
        if (content_cstr) env->ReleaseStringUTFChars(content_jstr, content_cstr);

        chat_msgs[i].role = role_storage[i].c_str();
        chat_msgs[i].content = content_storage[i].c_str();

        env->DeleteLocalRef(msg_class);
        env->DeleteLocalRef(msg);
    }

    int32_t total_chars = 0;
    for (const auto & m : chat_msgs) {
        if (m.content) total_chars += strlen(m.content);
    }
    int32_t buf_size = std::max(4096, total_chars * 2);

    std::vector<char> buf(buf_size);
    // nullptr = use model's built-in template. If the model has none,
    // return null so Kotlin can use its own fallback.
    int32_t result = llama_chat_apply_template(
        nullptr,
        chat_msgs.data(), chat_msgs.size(),
        add_ass,
        buf.data(), buf_size
    );

    if (result > buf_size) {
        buf.resize(result);
        result = llama_chat_apply_template(
            nullptr,
            chat_msgs.data(), chat_msgs.size(),
            add_ass,
            buf.data(), result
        );
    }

    if (result < 0) {
        LOGE("llama_chat_apply_template failed with %d", result);
        return nullptr;
    }

    return env->NewStringUTF(buf.data());
}

JNIEXPORT jint JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatGenerate(
    JNIEnv * env, jclass /*clazz*/, jlong handle_ptr,
    jstring prompt, jfloat temperature, jfloat top_p, jint max_tokens,
    jobject callback) {

    if (!handle_ptr) return -1;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    if (!handle->ctx || !handle->vocab) return -1;

    // Reset cancelled flag
    handle->cancelled = false;

    const char * prompt_str = env->GetStringUTFChars(prompt, nullptr);
    if (!prompt_str) return -1;

    std::string prompt_text(prompt_str);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    if (prompt_text.empty()) return -1;

    // Get callback class and method IDs
    jclass cb_class = env->GetObjectClass(callback);
    jmethodID on_token = env->GetMethodID(cb_class, "onToken", "(Ljava/lang/String;)V");
    jmethodID on_done = env->GetMethodID(cb_class, "onDone", "()V");
    jmethodID on_error = env->GetMethodID(cb_class, "onError", "(Ljava/lang/String;)V");

    if (!on_token || !on_done || !on_error) {
        LOGE("Failed to get callback method IDs");
        env->DeleteLocalRef(cb_class);
        return -1;
    }

    // Tokenize the prompt
    int32_t n_tokens_max = prompt_text.length() + 256;
    std::vector<llama_token> tokens(n_tokens_max);
    int32_t n_tokens = llama_tokenize(handle->vocab, prompt_text.c_str(),
                                       prompt_text.size(), tokens.data(),
                                       n_tokens_max, true, true);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(handle->vocab, prompt_text.c_str(),
                                   prompt_text.size(), tokens.data(),
                                   -n_tokens, true, true);
    }
    if (n_tokens <= 0) {
        LOGE("Tokenization returned 0 tokens for prompt len=%zu", prompt_text.size());
        env->CallVoidMethod(callback, on_error, env->NewStringUTF("Tokenization failed"));
        env->DeleteLocalRef(cb_class);
        return -1;
    }
    tokens.resize(n_tokens);

    // Limit tokens to context size
    if (n_tokens >= handle->n_ctx) {
        n_tokens = handle->n_ctx - 4; // leave room for generation
        tokens.resize(n_tokens);
    }

    LOGD("Generating: prompt_len=%zu, n_tokens=%d, max_tokens=%d",
         prompt_text.size(), n_tokens, max_tokens);

    // Prefill: create batch with all prompt tokens
    llama_batch batch = llama_batch_init(n_tokens, 0, 1);
    for (int i = 0; i < n_tokens; i++) {
        batch.token[i]    = tokens[i];
        batch.pos[i]      = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0]= 0;
        batch.logits[i]   = 0;
    }
    batch.logits[n_tokens - 1] = 1; // only need logits for last token
    batch.n_tokens = n_tokens;

    if (llama_decode(handle->ctx, batch) != 0) {
        LOGE("Prefill decode failed");
        llama_batch_free(batch);
        env->CallVoidMethod(callback, on_error, env->NewStringUTF("Prefill decode failed"));
        env->DeleteLocalRef(cb_class);
        return -1;
    }
    llama_batch_free(batch);

    // Set up sampler chain
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // Generation loop
    int32_t generated = 0;
    llama_token prev_token = tokens.back();

    for (int i = 0; i < max_tokens; i++) {
        if (handle->cancelled) {
            LOGD("Generation cancelled at %d tokens", generated);
            break;
        }

        // Sample the next token
        llama_token id = llama_sampler_sample(smpl, handle->ctx, -1);

        if (llama_vocab_is_eog(handle->vocab, id)) {
            LOGD("EOG token %d at position %d", id, generated);
            break;
        }

        // Convert token to text
        char buf[256];
        int32_t n_chars = llama_token_to_piece(handle->vocab, id, buf, sizeof(buf), 0, true);
        if (n_chars < 0) {
            LOGE("llama_token_to_piece failed");
            break;
        }
        std::string token_text(buf, n_chars);

        // Call back to Kotlin
        jstring jtoken = env->NewStringUTF(token_text.c_str());
        env->CallVoidMethod(callback, on_token, jtoken);
        env->DeleteLocalRef(jtoken);

        // Check for Java exception from callback
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            LOGD("Java exception in onToken, stopping generation");
            break;
        }

        generated++;

        // Decode the new token
        llama_batch single = llama_batch_get_one(&id, 1);
        if (llama_decode(handle->ctx, single) != 0) {
            LOGE("Decode failed at token %d", generated);
            break;
        }
    }

    llama_sampler_free(smpl);
    env->CallVoidMethod(callback, on_done);
    env->DeleteLocalRef(cb_class);

    LOGD("Generation complete: %d tokens generated", generated);
    return generated;
}

JNIEXPORT void JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatReset(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle_ptr) {

    if (!handle_ptr) return;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    if (handle->ctx) {
        llama_memory_clear(llama_get_memory(handle->ctx), true);
        LOGD("KV cache cleared");
    }
}

JNIEXPORT void JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatFreeModel(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle_ptr) {

    if (!handle_ptr) return;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);

    if (handle->ctx)   llama_free(handle->ctx);
    if (handle->model) llama_model_free(handle->model);

    LOGD("Chat model freed");
    delete handle;
}

JNIEXPORT void JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatCancel(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle_ptr) {

    if (!handle_ptr) return;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    handle->cancelled = true;
    LOGD("Cancellation requested");
}

} // extern "C"
