﻿using System;
using System.Collections.Concurrent;
using System.IO;
using System.Net;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace WinCAPTCHA.ServiceHandler;

/* 用于启动 WebSocket 服务器 */
public class WebSocketHandler
{
    private HttpListener? _httpListener;

    /** 控制 WebSocket 服务启动和停止时的并发访问 */
    private readonly SemaphoreSlim _startStopSemaphore = new(1, 1);

    /** 存储从 App 端接收到的消息 */
    private static readonly ConcurrentQueue<string> ReceivedMessages = new();

    /** 订阅消息事件 */
    public event Action<string>? OnMessageReceived;


    /** WebSocket 服务监听与保活 */
    private async Task MonitorWebSocketServer(IPAddress ipAddress, string port)
    {
        while (true)
        {
            if (_httpListener is not { IsListening: true })
            {
                Console.WriteLine("WebSocket 服务停止，尝试重新启动...");
                await StartWebSocketServer(ipAddress, port);
            }

            await Task.Delay(3600000); // 检查间隔
        }
    }

    public async Task StartWebSocketServer(IPAddress ipAddress, string port)
    {
        if (_httpListener != null)
        {
            Console.WriteLine("WebSocket 服务器已在运行");
            return;
        }

        await _startStopSemaphore.WaitAsync();
        try
        {
            if (_httpListener == null) // 防止并发初始化
            {
                _httpListener = new HttpListener();
                _httpListener.Prefixes.Add($"http://{ipAddress}:{port}/");

                _httpListener.Start();
                Console.WriteLine($"WebSocket 服务器已启动 - IP地址: {ipAddress},监听端口: {port}");

                _ = MonitorWebSocketServer(ipAddress, port);
                _ = AcceptWebSocketClientsAsync();
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine($"启动 WebSocket 服务器时发生错误: {ex.Message}");
            throw;
        }
        finally
        {
            _startStopSemaphore.Release();
        }
    }

    /** 持续监听并接受来自客户端的 WebSocket 连接请求 */
    private async Task AcceptWebSocketClientsAsync()
    {
        while (_httpListener?.IsListening == true)
        {
            var context = await _httpListener.GetContextAsync();
            if (context.Request.IsWebSocketRequest)
            {
                var webSocketContext = await context.AcceptWebSocketAsync(null);
                _ = HandleWebSocketConnectionAsync(webSocketContext.WebSocket);
            }
            else
            {
                // 回应客户端的 Http 请求
                if (context.Request.Url?.AbsolutePath == "/ping")
                {
                    context.Response.StatusCode = 200;
                    await using (var writer = new StreamWriter(context.Response.OutputStream))
                    {
                        await writer.WriteAsync("Pong");
                    }

                    context.Response.Close();
                }
                else
                {
                    context.Response.StatusCode = 400;
                    context.Response.Close();
                }
            }
        }
    }

    /** 处理客户端的 WebSocket 连接请求 */
    private async Task HandleWebSocketConnectionAsync(WebSocket webSocket)
    {
        var buffer = new byte[1024];
        while (webSocket.State == WebSocketState.Open)
        {
            var result = await webSocket.ReceiveAsync(new ArraySegment<byte>(buffer), CancellationToken.None);
            if (result.MessageType != WebSocketMessageType.Text)
            {
                if (result.MessageType == WebSocketMessageType.Close)
                {
                    await webSocket.CloseAsync(WebSocketCloseStatus.NormalClosure, "Closing", CancellationToken.None);
                }
            }
            else
            {
                var message = Encoding.UTF8.GetString(buffer, 0, result.Count);
                Console.WriteLine(DateTime.Now + message);
                if (string.IsNullOrWhiteSpace(message))
                {
                    Console.WriteLine("收到空消息，忽略处理");
                    continue;
                }

                ReceivedMessages.Enqueue(message);

                try
                {
                    OnMessageReceived?.Invoke(message);
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"消息处理订阅触发异常: {ex.Message}");
                }

                // 发送确认消息给 App 端
                var responseMessage = $"已收到消息: {message}";
                var responseBuffer = Encoding.UTF8.GetBytes(responseMessage);
                await webSocket.SendAsync(new ArraySegment<byte>(responseBuffer), WebSocketMessageType.Text, true,
                    CancellationToken.None);
                Console.WriteLine($"{DateTime.Now}已发送确认消息 ");
            }
        }
    }

    public async Task StopWebSocketServer()
    {
        if (_httpListener == null)
        {
            Console.WriteLine("WebSocket 服务器未运行");
            return;
        }

        await _startStopSemaphore.WaitAsync();
        try
        {
            if (_httpListener != null) // 防止并发释放
            {
                _httpListener.Stop();
                _httpListener = null;
                Console.WriteLine("WebSocket 服务器已停止");
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine($"停止 WebSocket 服务器时发生错误: {ex.Message}");
        }
        finally
        {
            _startStopSemaphore.Release();
        }
    }
}