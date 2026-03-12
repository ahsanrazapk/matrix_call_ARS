import 'package:flutter/material.dart';


class CallActionButton extends StatelessWidget {
  const CallActionButton({
    super.key,
    this.onTap,
    required this.icon,
    this.isEnabled = true,
    this.bgColor,
    this.radius = 24
  });

  final Function()? onTap;
  final Widget icon; // Change this to Widget
  final bool isEnabled;
  final Color? bgColor;

  final double? radius;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(32),
      onTap:  onTap,
      child: CircleAvatar(
        radius: radius,
        backgroundColor: bgColor,
        child: icon,
      ),
    );
  }
}

