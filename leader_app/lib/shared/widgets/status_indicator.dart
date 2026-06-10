import 'package:flutter/material.dart';
import 'package:hdc_mobile/core/theme/app_theme.dart';

class StatusIndicator extends StatelessWidget {
  const StatusIndicator({
    super.key,
    required this.isOnline,
    this.size = 8.0,
  });

  final bool isOnline;
  final double size;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: isOnline ? AppColors.online : AppColors.offline,
        boxShadow: isOnline
            ? [
                BoxShadow(
                  color: AppColors.online.withValues(alpha: 0.4),
                  blurRadius: size * 0.8,
                  spreadRadius: size * 0.2,
                ),
              ]
            : null,
      ),
    );
  }
}
