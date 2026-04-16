package org.example.utils;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Component
public class AliyunOSSOperator {

    @Autowired
    private AliyunOSSProperties aliyunOSSProperties;

    // 从配置文件中读取配置（推荐）
//    @Value("${aliyun.oss.endpoint}")
//    private String endpoint;
//
//    @Value("${aliyun.oss.bucketName}")
//    private String bucketName;
//
//    @Value("${aliyun.oss.accessKeyId}")
//    private String accessKeyId;
//
//    @Value("${aliyun.oss.accessKeySecret}")
//    private String accessKeySecret;

    //通过封装类


    // 或者直接在这里硬编码（不推荐生产环境使用，仅用于测试）

    /**
     * 上传文件到阿里云OSS
     * @param content 文件内容字节数组
     * @param originalFilename 原始文件名
     * @return 文件的访问URL
     * @throws Exception 上传异常
     */
    public String upload(byte[] content, String originalFilename) throws Exception {
        String endpoint =  aliyunOSSProperties.getEndpoint();
        String bucketName = aliyunOSSProperties.getBucketName();
        String accessKeyId = aliyunOSSProperties.getAccessKeyId();
        String accessKeySecret = aliyunOSSProperties.getAccessKeySecret();
        // 生成文件存储路径：yyyy/MM/uuid.扩展名
        String dir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
        String newFileName = UUID.randomUUID() + originalFilename.substring(originalFilename.lastIndexOf("."));
        String objectName = dir + "/" + newFileName;

        // 创建OSSClient实例 - 使用方案2的简单方式
        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);

        try {
            // 上传文件
            ossClient.putObject(bucketName, objectName, new ByteArrayInputStream(content));

            // 生成访问URL
            return generateFileUrl(objectName);

        } finally {
            // 关闭OSSClient
            ossClient.shutdown();
        }
    }

    /**
     * 上传文件（直接传File对象）
     * @param file java.io.File对象
     * @param originalFilename 原始文件名
     * @return 文件的访问URL
     * @throws Exception 上传异常
     */
    public String upload(java.io.File file, String originalFilename) throws Exception {
        String endpoint =  aliyunOSSProperties.getEndpoint();
        String bucketName = aliyunOSSProperties.getBucketName();
        String accessKeyId = aliyunOSSProperties.getAccessKeyId();
        String accessKeySecret = aliyunOSSProperties.getAccessKeySecret();
        String dir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
        String newFileName = UUID.randomUUID() + originalFilename.substring(originalFilename.lastIndexOf("."));
        String objectName = dir + "/" + newFileName;

        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);

        try {
            ossClient.putObject(bucketName, objectName, file);
            return generateFileUrl(objectName);
        } finally {
            ossClient.shutdown();
        }
    }

    /**
     * 生成文件的访问URL
     * @param objectName OSS中的文件路径
     * @return 完整的访问URL
     */
    private String generateFileUrl(String objectName) {
        String endpoint =  aliyunOSSProperties.getEndpoint();
        String bucketName = aliyunOSSProperties.getBucketName();
        String accessKeyId = aliyunOSSProperties.getAccessKeyId();
        String accessKeySecret = aliyunOSSProperties.getAccessKeySecret();
        // 格式：https://bucketName.endpoint/objectName
        return "https://" + bucketName + "." + endpoint.replace("https://", "") + "/" + objectName;
    }

    /**
     * 删除OSS中的文件
     * @param objectName OSS中的文件路径
     */
    public void delete(String objectName) {
        String endpoint =  aliyunOSSProperties.getEndpoint();
        String bucketName = aliyunOSSProperties.getBucketName();
        String accessKeyId = aliyunOSSProperties.getAccessKeyId();
        String accessKeySecret = aliyunOSSProperties.getAccessKeySecret();
        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        try {
            ossClient.deleteObject(bucketName, objectName);
        } finally {
            ossClient.shutdown();
        }
    }
}