package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BOLG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {


    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryBolgById(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBolgById(Long id) {
        // 查询bolg
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("不存在的id");
        }
        //查询blog有关用户
        queryBlogUser(blog);
        //判断是否点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }


    @Override
    public Result likeblog(Long id) {
        //1查询登录用户
        Long userId = UserHolder.getUser().getId();
        //2判断用户是否已经点赞
        String key = BOLG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId);
        if (score == null) {
            //3未点赞
            //3.1 可以点赞更新点赞数
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess) {
                //3.2 添加到点赞集合中
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            //4已经点赞
            //4.1 可以取消点赞
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess) {
                //4.2 从点赞集合中移除
                stringRedisTemplate.opsForSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBolgLikes(Long id) {
        String key = BOLG_LIKED_KEY + id;
        //1查询前五个点赞的
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());

        }
        //2解析其中的用户id
        List<Long> idCollect = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", idCollect);
        //3根据用户id查找用户
        List<UserDTO> userDTOS = userService.query()
                .in("id", idCollect)
                .last("ORDER BY FIELD(id," +idStr+")" ).list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //4返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {

        //1获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        //2保存笔记
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("笔记保存失败");
        }
        //3查询粉丝 select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //4推送笔记给粉丝
        for (Follow follow : follows) {
            //4.1获取粉丝
            Long followUserId = follow.getUserId();
            String key =FEED_KEY + followUserId;
            //4.2推送
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        //返回
        return Result.ok(blog.getId());
    }

    //@Override
    //public Result queryBlogOfFollow(Long lastId, Integer offset) {
        //1获取登录用户

        //2查询收件箱

        //3解析数据bolgId，minTime（时间戳）,offset

        //
        //return null;
    //}
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2. 查询该用户收件箱（之前我们存的key是固定前缀 + 粉丝id），所以根据当前用户id就可以查询是否有关注的人发了笔记
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typeTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 10);// 一次最多10条
        //3. 非空判断
        if (typeTuples == null || typeTuples.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //4. 解析数据，blogId、minTime（时间戳）、offset，这里指定创建的list大小，可以略微提高效率，因为我们知道这个list就得是这么大
        List<Long> blogIds = new ArrayList<>(typeTuples.size());
        long minTime = 0;
        int newOffset = 1;
        for (ZSetOperations.TypedTuple<String> typeTuple : typeTuples) {
            //4.1 获取id
            String id = typeTuple.getValue();
            blogIds.add(Long.valueOf(id));

            //4.2 获取score（时间戳）
            long time = typeTuple.getScore().longValue();
            if (time == minTime){
                newOffset++;
            }else {
                minTime = time;
                newOffset = 1;
            }
        }
        // 4. 用 listByIds()，它内部自动处理：
        //    - 空集合安全
        //    - 自动生成 IN (?, ?, ?)
        //    - 自动加上 ORDER BY FIELD(id, ?, ?, ?)
        List<Blog> blogs = listByIds(blogIds);

        if (CollUtil.isEmpty(blogs)) {
            return Result.ok(Collections.emptyList());
        }

        // 5. 手动按收件箱顺序排序（因为 listByIds 的 FIELD 排序可能和我们期望的顺序不完全一致）
        //    这一步保留原始时间线顺序，超级重要！
        blogs.sort(Comparator.comparingInt(b -> blogIds.indexOf(b.getId())));

        // 6. 查询用户信息 + 点赞状态（不变）
        for (Blog blog : blogs) {
            queryBlogUser(blog);
            isBlogLiked(blog);
        }

        // 7. 封装返回
        ScrollResult result = new ScrollResult();
        result.setList(blogs);
        result.setMinTime(minTime);
        result.setOffset(newOffset);

        return Result.ok(result);
    }

    //判断用户是否已经点
    private void isBlogLiked(Blog blog) {
        UserDTO userDTO = UserHolder.getUser();
        if (userDTO == null){
            return;
        }
        Long userId = userDTO.getId();
        //2判断用户是否已经点赞
        String key = BOLG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId);
        blog.setIsLike(score != null);
    }

    // 查询blog有关用户
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}